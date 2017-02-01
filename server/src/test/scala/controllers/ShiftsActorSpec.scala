package controllers

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{After, Specification}
import services.WorkloadCalculatorTests.apiFlight
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import collection.JavaConversions._


abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
  "akka.persistence.journal.leveldb.dir" -> "target/test",
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local"
)).withFallback(ConfigFactory.load("application.conf"))))
  with After
  with ImplicitSender {
  def after =  {
    shutDownActorSystem
    new File("target/test").listFiles().map(_.delete())
  }

  def shutDownActorSystem = {
    Await.ready(system.terminate(), 1 second)
  }
}

class ShiftsActorSpec extends Specification {
  sequential

  "ShiftsActor" should {

    "return the message it that was set if only one message is sent" in new AkkaTestkitSpecs2Support {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! GetState

      expectMsg("first message")

    }
    "should return the most recent message if more than one message is sent" in new AkkaTestkitSpecs2Support {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! GetState

      expectMsg("second message")
    }

    "should restore the most recent message sent after a restart" in new AkkaTestkitSpecs2Support {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! "third message"

      actor ! GetState

      expectMsg("third message")

      shutDownActorSystem

      new AkkaTestkitSpecs2Support {

        val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

        actor ! GetState

        expectMsg("third message")
      }
    }

    "should return recent message if a message is sent after a restart" in new AkkaTestkitSpecs2Support {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! "third message"

      actor ! GetState

      expectMsg("third message")

      shutDownActorSystem

      new AkkaTestkitSpecs2Support {

        val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")
        actor ! "fourth message"
        actor ! GetState

        expectMsg("fourth message")
      }
    }
  }
}
