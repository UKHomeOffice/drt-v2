package services

import spatutorial.shared.AirportInfo
import utest._

import spatutorial.shared._
import utest._

import akka.actor.ActorSystem
import scala.concurrent.{Future, Await}
import scala.util.{Success, Failure}
import scala.util.Try
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.testkit.{TestKit, TestActors, DefaultTimeout, ImplicitSender }
import controllers.SystemActors
import controllers.Core

object AirportToCountryTests extends TestSuite {
  def tests = TestSuite {
    "can load csv" - {
      val result = AirportToCountry.airportInfo.get("GKA")
      val expected = Some(AirportInfo("Goroka", "Goroka", "Papua New Guinea", "GKA"))
      assert(result == expected)
    }
    "can ask the apiservice for LGW" - {
      val airportInfo = AirportToCountry.airportInfoByAirportCode("LGW")
      airportInfo.onSuccess {
        case Some(ai) =>
          println(s"i'm asserting ${ai}")
          assert(ai == AirportInfo("Gatwick", "London",  "United Kingdom", "LGW"))
        case f =>
          println(f)
          assert(false)
      }
    }
  }
}

