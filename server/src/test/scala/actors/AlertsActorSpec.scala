package actors

import java.util.UUID
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import controllers.AkkaTestkitSpecs2SupportForPersistence
import drt.shared.Alert
import org.joda.time.DateTime
import org.specs2.matcher.Scope
import org.specs2.mutable.Specification
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class AlertsActorSpec extends Specification {
  sequential
  isolated

  private def alertsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[AlertsActor]), "alertsActor")
    actor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def getTestKit = {
    new AkkaTestkitSpecs2SupportForPersistence("target/test") {
      def getActor: ActorRef = alertsActor(system)

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

  "AlertsActor" should {
    "return the message it that was set if only one message is sent" in new Context {

      val alert = Alert("alert title", "this is the alert message", DateTime.now.plusDays(1).getMillis, DateTime.now.getMillis)

      actor ! alert

      val result = testKit2.getStateAndShutdown(actor)

      result mustEqual List(alert)
    }
  }

}
