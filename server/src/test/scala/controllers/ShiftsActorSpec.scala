package controllers

import java.io.File

import actors.{GetState, ShiftsActor, ShiftsMessageParser}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.MilliDate
import org.joda.time.format.DateTimeFormat
import org.specs2.mutable.{After, Before, Specification}
import org.specs2.specification.AfterAll

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object PersistenceCleanup {
  def deleteJournal(dbLocation: String): Unit = {
    val directory = new File(dbLocation)
    Option(directory.listFiles())
      .map(files => files.map((file: File) => {
        val result = if (file.isDirectory) deleteJournal(file.getPath) else file.delete()
      }))
  }
}


abstract class AkkaTestkitSpecs2SupportForPersistence(val dbLocation: String) extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
  "akka.persistence.journal.leveldb.dir" -> dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"$dbLocation/snapshot"
))))
  with After
  with ImplicitSender {

  def after = {
    shutDownActorSystem
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
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
  isolated

  private def shiftsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[ShiftsActor]), "shiftsactor")
    actor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def getTestKit = {
    new AkkaTestkitSpecs2SupportForPersistence("target/test") {
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
      actor ! "shift name, T1, 20/01/17, 10:00, 20:00, 9"

      val result = testKit2.getStateAndShutdown(actor)
      result === "shift name, T1, 20/01/17, 10:00, 20:00, 9"
    }

    "return the most recent message if more than one message is sent" in {
      val testKit2 = getTestKit
      val actor = testKit2.getActor
      actor ! "shift name, T1, 20/01/17, 10:00, 20:00, 9"
      actor ! "another name, T1, 20/01/17, 10:00, 20:00, 9"
      val result = testKit2.getStateAndShutdown(actor)
      result === "another name, T1, 20/01/17, 10:00, 20:00, 9"
    }

    "restore the most recent message sent after a restart" in {
      val testKit1 = getTestKit
      val actor = testKit1.getActor
      actor ! "shift name, T1, 20/01/17, 10:00, 20:00, 9"
      actor ! "another name, T1, 20/01/17, 10:00, 20:00, 9"
      actor ! PoisonPill
      testKit1.shutDownActorSystem

      val testKit2 = getTestKit
      val actor2 = testKit2.getActor
      val result = testKit2.getStateAndShutdown(actor2)

      result === "another name, T1, 20/01/17, 10:00, 20:00, 9"
    }

    "return recent message if a message is sent after a restart" in {

      val testKit1 = getTestKit
      val actor = testKit1.getActor
      actor ! "shift name, T1, 20/01/17, 10:00, 20:00, 9"
      actor ! "another name, T1, 20/01/17, 10:00, 20:00, 9"
      testKit1.shutDownActorSystem

      val testKit2 = getTestKit
      val actor2 = testKit2.getActor
      actor2 ! "third name, T1, 20/01/17, 10:00, 20:00, 9"
      val result = testKit2.getStateAndShutdown(actor2)

      result === "third name, T1, 20/01/17, 10:00, 20:00, 9"
    }


    "convert date and time into a timestamp" in {
      Some(1484906400000L) === ShiftsMessageParser.dateAndTimeToMillis("20/01/17", "10:00")
    }
    "return None if the date is incorrectly formatted" in {
      None === ShiftsMessageParser.dateAndTimeToMillis("adfgdfgdfg7", "10:00")
    }
    "get start and end date millis from the startDate, endTime and startTime when endTime is later than startTime" in {
      val result = ShiftsMessageParser.startAndEndTimestamps("20/01/17", "10:00", "11:00")
      result === (Some(1484906400000L), Some(1484910000000L))
    }
    "get start and end date millis from the startDate, endTime and startTime when endTime is earlier than startTime" in {
      val result = ShiftsMessageParser.startAndEndTimestamps("20/01/17", "10:00", "09:00")
      result === (Some(1484906400000L), Some(1484989200000L))
    }
    "get start and end date millis from the startDate, endTime and startTime given invalid data" in {
      val result = ShiftsMessageParser.startAndEndTimestamps("jkhsdfjhdsf", "10:00", "09:00")
      result === (None, None)
    }
    "convert timestamp to dateString" in {
      val timestamp = 1484906400000L

      ShiftsMessageParser.dateString(timestamp) === "20/01/17"
    }
    "convert timestamp to timeString" in {
      val timestamp = 1484906400000L

      ShiftsMessageParser.timeString(timestamp) === "10:00"
    }

    "ShiftsPersistenceApi" should {
      "allow setting and getting of shift data" in new AkkaTestkitSpecs2SupportForPersistence("target/test") {

        val shiftPersistenceApi = new ShiftPersistence {

          override implicit val timeout: Timeout = Timeout(5 seconds)

          val actorSystem = system
        }

        shiftPersistenceApi.saveShifts("shift name, T1, 20/01/17, 10:00, 20:00, 9")

        awaitAssert({
          val resultFuture = shiftPersistenceApi.getShifts(0L)
          val result = Await.result(resultFuture, 1 seconds)
          assert("shift name, T1, 20/01/17, 10:00, 20:00, 9" == result)
        }, 2 seconds)
      }
    }
  }
}
