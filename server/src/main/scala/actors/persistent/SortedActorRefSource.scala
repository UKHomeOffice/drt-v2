package actors.persistent

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._

import scala.collection.immutable.SortedSet

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource[T <: Ordered[T]](
                                                   completionMatcher: PartialFunction[Any, CompletionStrategy],
                                                   failureMatcher: PartialFunction[Any, Throwable])
  extends GraphStageWithMaterializedValue[SourceShape[T], ActorRef] {

  import SortedActorRefSource._

  val out: Outlet[T] = Outlet[T]("actorRefSource.out")

  override val shape: SourceShape[T] = SourceShape.of(out)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ActorRef) =
    throw new IllegalStateException("Not supported")

  override def createLogicAndMaterializedValue(
                                                inheritedAttributes: Attributes,
                                                eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
      with ActorRefStage {
      override protected def logSource: Class[_] = classOf[SortedActorRefSource[_]]

      private var buffer: SortedSet[T] = SortedSet[T]()

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer, poisonPillCompatibility = true) {
        case (_, m: T@unchecked) =>
          buffer = buffer + m
      }.ref

      private def tryPush(): Unit = {
        if (isAvailable(out)) {
          buffer.headOption.foreach { e =>
            buffer = buffer.drop(1)
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
