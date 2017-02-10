package controllers

import java.io.File

import actors.{GetState, ShiftsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{After, Specification}

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

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
    Thread.sleep(200)
    Await.ready(system.terminate(), 2 second)
    Await.ready(system.whenTerminated, 2 second)
  }
}

class ShiftsActorSpec extends Specification {
  sequential


  private def shiftsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsactor")
    actor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def getTestKit = {
    new AkkaTestkitSpecs2Support("target/test") {
      def getActor = shiftsActor(system)
      def getState(actor: ActorRef) = {
        Await.result(actor ? GetState, 1 second)
      }
      def getStateAndShutdown(actor: ActorRef) = {
        val s = getState(actor)
        shutDownActorSystem
        s
      }
    }
  }

  "ShiftsActor" should {
    "return the message it that was set if only one message is sent" in {
      val testKit2 = getTestKit
      val actor = testKit2.getActor
      actor ! "first message"
      
      val result = testKit2.getStateAndShutdown(actor)
      result == "first message"
    }

    "return the most recent message if more than one message is sent" in {
      val testKit2 = getTestKit
      val actor = testKit2.getActor
      actor ! "first message"
      actor ! "second message"
      val result = testKit2.getStateAndShutdown(actor)
      result == "second message"
    }

    "restore the most recent message sent after a restart" in {
      val testKit1 = getTestKit
      val actor = testKit1.getActor
      actor ! "first message"
      actor ! "second message"
      testKit1.shutDownActorSystem

      val testKit2 = getTestKit
      val actor2 = testKit2.getActor
      val result = testKit2.getStateAndShutdown(actor2)

      result == "second message"
    }

    "return recent message if a message is sent after a restart" in {

      val testKit1 = getTestKit
      val actor = testKit1.getActor
      actor ! "first message"
      actor ! "second message"
      testKit1.shutDownActorSystem

      val testKit2 = getTestKit
      val actor2 = testKit2.getActor
      actor2 ! "newest message"
      val result = testKit2.getStateAndShutdown(actor2)

      result == "newest message"
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
