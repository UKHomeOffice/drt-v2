package actors.persistent

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream._
import org.apache.pekko.stream.stage._
import uk.gov.homeoffice.drt.actor.commands.{RemoveProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.{SortedSet, mutable}

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource(persistentActor: ActorRef,
                                 initialQueue: SortedSet[TerminalUpdateRequest],
                                 graphName: String,
                                )
  extends GraphStageWithMaterializedValue[SourceShape[TerminalUpdateRequest], ActorRef] {

  import SortedActorRefSource._

  val out: Outlet[TerminalUpdateRequest] = Outlet[TerminalUpdateRequest]("actorRefSource.out")

  override val shape: SourceShape[TerminalUpdateRequest] = SourceShape.of(out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ActorRef) =
    throw new IllegalStateException("Not supported")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes,
                                               eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
      with ActorRefStage {
      override protected def logSource: Class[_] = classOf[SortedActorRefSource]

      private val buffer: mutable.SortedSet[TerminalUpdateRequest] = mutable.SortedSet.empty[TerminalUpdateRequest] ++ initialQueue
      private var prioritiseForecast: Boolean = false

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer) {
        case (_, m: Iterable[_]) =>
          m.headOption
            .map {
              case _: TerminalUpdateRequest =>
                m.collect { case r: TerminalUpdateRequest => r }
              case unexpected =>
                log.error(s"[$graphName] Received unexpected message ${unexpected.getClass.getSimpleName}")
                Iterable.empty
            }
            .map { requests =>
              buffer ++= requests
              persistentActor ! m
              tryPushElement()
            }

        case (_, r: TerminalUpdateRequest) =>
          buffer += r
          persistentActor ! r
          tryPushElement()

        case unexpected =>
          log.error(s"[$graphName] Ignoring unexpected message: $unexpected")
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
