package controllers

import java.io.File
import java.util.concurrent.TimeUnit

import actors.{GetState, ShiftsActor}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{After, Specification}
import services.WorkloadCalculatorTests.apiFlight
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import collection.JavaConversions._
import scala.util.Success
import scala.concurrent._
import ExecutionContext.Implicits.global
import akka.pattern._

abstract class AkkaTestkitSpecs2Support(dbLocation: String) extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
  "akka.persistence.journal.leveldb.dir" -> dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local"
)).withFallback(ConfigFactory.load(getClass.getResource("/application.conf").getPath.toString))))
  with After
  with ImplicitSender {

  def after = {
    shutDownActorSystem
    new File(dbLocation).listFiles().map(_.delete())
  }

  def shutDownActorSystem = {
    //TODO figure out how to wait for the actor to finish saving rather than this nasty timer.
    Thread.sleep(20)
    Await.ready(system.terminate(), 2 second)
    Await.ready(system.whenTerminated, 2 second)
  }
}

class ShiftsActorSpec extends Specification {
  sequential

  "ShiftsActor" should {

    "return the message it that was set if only one message is sent" in new AkkaTestkitSpecs2Support("target/test") {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! GetState

      expectMsg("first message")

    }

    "return the most recent message if more than one message is sent" in new AkkaTestkitSpecs2Support("target/test") {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! GetState

      expectMsg("second message")
    }

    "restore the most recent message sent after a restart" in new AkkaTestkitSpecs2Support("target/test") {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! "third message"

      actor ! GetState

      expectMsg("third message")

      shutDownActorSystem

      new AkkaTestkitSpecs2Support("target/test") {

        val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

        actor ! GetState

        expectMsg("third message")
      }
    }

    "return recent message if a message is sent after a restart" in new AkkaTestkitSpecs2Support("target/test") {

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "first message"

      actor ! "second message"

      actor ! "third message"

      actor ! GetState

      expectMsg("third message")

      shutDownActorSystem

      new AkkaTestkitSpecs2Support("target/test") {

        val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")
        actor ! "fourth message"
        actor ! GetState

        expectMsg("fourth message")
      }
    }

    "return the shift when asked using GetState" in new AkkaTestkitSpecs2Support("target/test") {
      implicit val timeout: Timeout = Timeout(5 seconds)

      val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsActor")

      actor ! "test shifts"

      val futureResult = actor ? GetState

      val result = Await.result(futureResult, 1 second)

      assert("test shifts" == result)

    }

    "ShiftsPersistenceApi" should {
      "allow setting and getting of shift data" in new AkkaTestkitSpecs2Support("target/test") {

        val shiftPersistenceApi = new ShiftPersistence {

          override implicit val timeout: Timeout = Timeout(5 seconds)

          val actorSystem = system
        }

        shiftPersistenceApi.saveShifts("test shifts")

        awaitAssert({
          val resultFuture = shiftPersistenceApi.getShifts()
          val result = Await.result(resultFuture, 1 seconds)
          assert("test shifts" == result)
        }, 2 seconds)
      }
    }
  }
}
