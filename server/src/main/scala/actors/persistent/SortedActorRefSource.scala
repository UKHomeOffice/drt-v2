package actors.persistent

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.actor.commands.{ProcessingRequest, RemoveProcessingRequest}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.{SortedSet, mutable}

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource[A <: ProcessingRequest](persistentActor: ActorRef,
                                                         processingRequest: MillisSinceEpoch => A,
                                                         initialQueue: SortedSet[A],
                                                         graphName: String,
                                                        )
  extends GraphStageWithMaterializedValue[SourceShape[A], ActorRef] {

  import SortedActorRefSource._

  val out: Outlet[A] = Outlet[A]("actorRefSource.out")

  override val shape: SourceShape[A] = SourceShape.of(out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ActorRef) =
    throw new IllegalStateException("Not supported")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes,
                                               eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
      with ActorRefStage {
      override protected def logSource: Class[_] = classOf[SortedActorRefSource[A]]

      private val buffer: mutable.SortedSet[A] = mutable.SortedSet[A]() ++ initialQueue
      private var prioritiseForecast: Boolean = false

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer) {
        case (_, m: Iterable[A @unchecked]) =>
          buffer ++= m
          persistentActor ! m
          tryPushElement()

        case (_, m: A @unchecked) =>
          buffer += m
          persistentActor ! m
          tryPushElement()

        case (_, UpdatedMillis(millis)) =>
          val requests = millis.map(processingRequest)
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
          val forecastRequests = buffer.filter { r =>
            SDate(r.date) > SDate.now()
          }
          val maybeNextElement = if (prioritiseForecast && forecastRequests.nonEmpty) forecastRequests.headOption else buffer.headOption
          maybeNextElement.foreach { e =>
            persistentActor ! RemoveProcessingRequest(e)
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
