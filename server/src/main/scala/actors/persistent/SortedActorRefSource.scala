package actors.persistent

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream._
import akka.stream.stage._
import drt.shared.SDateLike
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, RemoveCrunchRequest}

import scala.collection.mutable

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource(persistentActor: ActorRef, crunchOffsetMinutes: Int, durationMinutes: Int)(implicit system: ActorSystem)
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


      val ref: ActorRef = getEagerStageActor(eagerMaterializer, poisonPillCompatibility = true) {
        case (_, m: CrunchRequest @unchecked) =>
          buffer += m
          persistentActor ! m
          tryPushElement()
        case (_, UpdatedMillis(millis)) =>
          val requests = millis.map(CrunchRequest(_, crunchOffsetMinutes, durationMinutes)).toSet
          requests.foreach(persistentActor ! _)
          buffer ++= requests
          tryPushElement()
      }.ref

      private def tryPushElement(): Unit = {
        println("\n\n**tryPush")
        if (isAvailable(out)) {
          println("\n\n**tryPush -> isAvailable")
          buffer.headOption.foreach { e =>
            persistentActor ! RemoveCrunchRequest(e)
            println(s"\n\n**tryPush -> isAvailable -> pushing $e")
            buffer -= e
            push(out, e)
          }
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          println(s"\n\n** onPull")
          tryPushElement()
        }
      })
    }

    (stage, stage.ref)
  }
}
