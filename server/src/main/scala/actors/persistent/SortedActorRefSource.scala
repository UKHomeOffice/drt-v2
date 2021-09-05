package actors.persistent

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream._
import akka.stream.stage._
import drt.shared.SDateLike
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest

import scala.collection.mutable

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource()(implicit system: ActorSystem)
  extends GraphStageWithMaterializedValue[SourceShape[CrunchRequest], ActorRef] {

  import SortedActorRefSource._

  val out: Outlet[CrunchRequest] = Outlet[CrunchRequest]("actorRefSource.out")

  override val shape: SourceShape[CrunchRequest] = SourceShape.of(out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ActorRef) =
    throw new IllegalStateException("Not supported")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes,
                                               eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
      with ActorRefStage {
      override protected def logSource: Class[_] = classOf[SortedActorRefSource]

      private val buffer: mutable.SortedSet[CrunchRequest] = mutable.SortedSet[CrunchRequest]()

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      class CrunchQueueActor(now: () => SDateLike,
                             crunchOffsetMinutes: Int,
                             durationMinutes: Int) extends QueueLikeActor(now,  crunchOffsetMinutes, durationMinutes) {
        override val persistenceId: String = "crunch-queue"
        override val queuedDays: mutable.SortedSet[CrunchRequest] = buffer
      }

      val ref: ActorRef = system.actorOf(Props(new CrunchQueueActor(now = () => SDate.now(), 0, 1440)))

      private def tryPush(): Unit = {
        if (isAvailable(out)) {
          buffer.headOption.foreach { e =>
            buffer -= 1
            push(out, e)
          }
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          tryPush()
        }
      })
    }

    (stage, stage.ref)
  }
}
