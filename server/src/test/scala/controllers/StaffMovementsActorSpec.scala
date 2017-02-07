package controllers

import java.io.File
import java.util.UUID
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
import spatutorial.shared.{MilliDate, StaffMovement}
import scala.collection.immutable.Seq

class StaffMovementsActorSpec extends Specification {
  sequential

  "StaffMovementsPersistenceApi" should {
    "allow setting and getting of staff movements" in new AkkaTestkitSpecs2Support {

      val staffMovementsApi = new StaffMovementsPersistence {
        override implicit val timeout: Timeout = Timeout(5 seconds)

        val actorSystem = system
      }

      private val uuid: UUID = UUID.randomUUID()
      staffMovementsApi.saveStaffMovements(Seq(
        StaffMovement("is81", MilliDate(0L), -1, uuid)
      ))

      awaitAssert({
        val resultFuture = staffMovementsApi.getStaffMovements()
        val result = Await.result(resultFuture, 1 seconds)
        assert(Seq(StaffMovement("is81", MilliDate(0L), -1, uuid)) == result)
      }, 2 seconds)
    }

  }
}
