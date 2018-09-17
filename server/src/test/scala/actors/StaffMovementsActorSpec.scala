package actors


import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import drt.shared.{MilliDate, StaffMovement}
import org.specs2.matcher.Scope
import org.specs2.mutable.Specification
import services.SDate

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class StaffMovementsActorSpec extends Specification {
  sequential
  isolated

  private def staffMovementsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[StaffMovementsActorBase]), "staffMovementsActor")
    actor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def getTestKit = {
    new AkkaTestkitSpecs2SupportForPersistence("target/test") {
      def getActor: ActorRef = staffMovementsActor(system)

      def getState(actor: ActorRef) = {
        Await.result(actor ? GetState, 1 second)
      }

      def getStateAndShutdown(actor: ActorRef): Any = {
        val s = getState(actor)
        shutDownActorSystem
        s
      }
    }
  }

  trait Context extends Scope {
    val testKit2 = getTestKit
    val actor: ActorRef = testKit2.getActor
    val date = "2017-01-01"
    val movementUuid: UUID = UUID.randomUUID()
  }

  "StaffMovementsActor" should {
    "return the message it that was set if only one message is sent" in new Context {

      val staffMovements = StaffMovements(Seq(StaffMovement("T1", "lunch start", MilliDate(SDate(s"${date}T00:00").millisSinceEpoch), -1, movementUuid, createdBy = Some("batman"))))

      actor ! staffMovements

      val result = testKit2.getStateAndShutdown(actor)

      result mustEqual staffMovements
    }
  }

}
