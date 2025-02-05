package actors

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import actors.persistent.{AlertsActor, DeleteAlerts}
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.pattern._
import org.apache.pekko.testkit.TestKit
import drt.shared.Alert
import org.joda.time.DateTime
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._


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
