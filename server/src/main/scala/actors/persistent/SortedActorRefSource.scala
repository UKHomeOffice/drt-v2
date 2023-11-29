package actors.persistent

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest, RemoveCrunchRequest}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.{SortedSet, mutable}

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource(persistentActor: ActorRef,
                                 crunchOffsetMinutes: Int,
                                 durationMinutes: Int,
                                 initialQueue: SortedSet[ProcessingRequest],
                                 graphName: String,
                                )
  extends GraphStageWithMaterializedValue[SourceShape[ProcessingRequest], ActorRef] {

  import SortedActorRefSource._

  val out: Outlet[ProcessingRequest] = Outlet[ProcessingRequest]("actorRefSource.out")

  override val shape: SourceShape[ProcessingRequest] = SourceShape.of(out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ActorRef) =
    throw new IllegalStateException("Not supported")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes,
                                               eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
      with ActorRefStage {
      override protected def logSource: Class[_] = classOf[SortedActorRefSource]

      private val buffer: mutable.SortedSet[ProcessingRequest] = mutable.SortedSet[ProcessingRequest]() ++ initialQueue
      private var prioritiseForecast: Boolean = false

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer) {
        case (_, m: Iterable[ProcessingRequest@unchecked]) =>
          buffer ++= m
          persistentActor ! m
          tryPushElement()

        case (_, m: ProcessingRequest@unchecked) =>
          buffer += m
          persistentActor ! m
          tryPushElement()

        case (_, UpdatedMillis(millis)) =>
          val requests = millis.map(CrunchRequest(_, crunchOffsetMinutes, durationMinutes))
          if (requests.nonEmpty) {
            requests.foreach(persistentActor ! _)
            buffer ++= requests
            tryPushElement()
          }

        case unexpected =>
          log.warning(s"[$graphName] Ignoring unexpected message: $unexpected")
      }.ref

      private def tryPushElement(): Unit = {
        if (isAvailable(out)) {
          val forecastRequests = buffer.filter(_.start > SDate.now())
          val maybeNextElement = if (prioritiseForecast && forecastRequests.nonEmpty) forecastRequests.headOption else buffer.headOption
          maybeNextElement.foreach { e =>
            persistentActor ! RemoveCrunchRequest(e)
            buffer -= e
            push(out, e)
            prioritiseForecast = !prioritiseForecast
          }
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          tryPushElement()
        }
      })
    }

    (stage, stage.ref)
  }

}
