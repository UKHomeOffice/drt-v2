package actors.persistent

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.stage._
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, ProcessingRequest, RemoveCrunchRequest}

import scala.collection.{SortedSet, mutable}

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource(persistentActor: ActorRef, crunchOffsetMinutes: Int, durationMinutes: Int, initialQueue: SortedSet[ProcessingRequest])
                                (implicit system: ActorSystem)
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
      private var getHead: Boolean = true

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer) {
        case (_, m: ProcessingRequest@unchecked) =>
          buffer += m
          persistentActor ! m
          tryPushElement()
        case (_, UpdatedMillis(millis)) =>
          val requests = millis.map(CrunchRequest(_, crunchOffsetMinutes, durationMinutes)).toSet
          requests.foreach(persistentActor ! _)
          buffer ++= requests
          tryPushElement()
        case unexpected =>
          log.warning(s"Ignoring unexpected message: $unexpected")
      }.ref

      private def tryPushElement(): Unit = {
        if (isAvailable(out)) {
          val maybeNextElement = if (getHead || buffer.size == 1) buffer.headOption else buffer.drop(1).headOption
          maybeNextElement.foreach { e =>
            persistentActor ! RemoveCrunchRequest(e)
            buffer -= e
            push(out, e)
            getHead = !getHead
          }
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info(s"SortedActorRefSource Pulled (with ${buffer.size} elements). isAvailable: ${isAvailable(out)}")
          tryPushElement()
        }
      })
    }

    (stage, stage.ref)
  }

}
