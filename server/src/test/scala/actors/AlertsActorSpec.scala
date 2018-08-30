package actors

import java.util.UUID
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import drt.shared.Alert
import org.joda.time.DateTime
import org.specs2.matcher.Scope
import org.specs2.mutable.{After, Specification}
import passengersplits.AkkaPersistTestConfig
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
    new AkkaTestkitSpecs2SupportForInMemoryPersistence() {
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

abstract class AkkaTestkitSpecs2SupportForInMemoryPersistence extends TestKit(ActorSystem("testActorSystem", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
  with After
  with ImplicitSender {

  def after = {
    shutDownActorSystem
  }

  def shutDownActorSystem = {
    //TODO figure out how to wait for the actor to finish saving rather than this nasty timer.
    Thread.sleep(200)
    import scala.language.postfixOps
    Await.ready(system.terminate(), 2 second)
    Await.ready(system.whenTerminated, 2 second)
  }
}