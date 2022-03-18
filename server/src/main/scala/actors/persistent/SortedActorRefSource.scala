package actors.persistent

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.stage._
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, RemoveCrunchRequest}

import scala.collection.{SortedSet, mutable}

private object SortedActorRefSource {
  private sealed trait ActorRefStage {
    def ref: ActorRef
  }
}

final class SortedActorRefSource(persistentActor: ActorRef, crunchOffsetMinutes: Int, durationMinutes: Int, initialQueue: SortedSet[CrunchRequest])
                                (implicit system: ActorSystem)
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

      private val buffer: mutable.SortedSet[CrunchRequest] = mutable.SortedSet[CrunchRequest]() ++ initialQueue

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer) {
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
        log.info(s"$getClass tryPushElement")
        if (isAvailable(out)) {
          log.info(s"$getClass tryPushElement isAvailable. buffer: ${buffer.map(cr => s"${cr.localDate.year}-${cr.localDate.month}-${cr.localDate.day}").mkString(", ")}")
          buffer.headOption.foreach { e =>
            log.info(s"$getClass tryPushElement pushing: $e")
            persistentActor ! RemoveCrunchRequest(e)
            buffer -= e
            push(out, e)
          }
        } else {
          log.info(s"$getClass tryPushElement is not available")
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info(s"onPull called")
          tryPushElement()
        }
      })
    }

    (stage, stage.ref)
  }
}
