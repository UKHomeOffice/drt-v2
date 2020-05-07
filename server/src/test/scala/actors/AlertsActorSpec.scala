package actors

import akka.actor.{ActorRef, Props}
import akka.pattern._
import akka.testkit.TestKit
import drt.shared.Alert
import org.joda.time.DateTime
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class AlertsActorSpec extends CrunchTestLike {
  sequential
  isolated

  val actor: ActorRef = system.actorOf(Props(classOf[AlertsActor], () => SDate.now()), "alertsActor")

  "AlertsActor" should {
    "return the message it that was set if only one message is sent" >> {

      val alert = Alert("alert title", "this is the alert message", "notice", DateTime.now.plusDays(1).getMillis, DateTime.now.getMillis)

      actor ! DeleteAlerts
      Thread.sleep(250)

      actor ! alert

      val result = Await.result(actor ? GetState, 1 second)
      TestKit.shutdownActorSystem(system)

      result mustEqual List(alert)
    }
  }
}
