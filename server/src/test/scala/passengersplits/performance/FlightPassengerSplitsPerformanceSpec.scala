package passengersplits.performance

import akka.actor.{Props, _}
import akka.event.Logging
import akka.pattern.AskableActorRef
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalacheck.{Arbitrary, Gen}
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterAll
import passengersplits.PassengerInfoBatchActor
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import passengersplits.core.{FlatPassengerSplitsInfoByPortRouter, PassengerSplitsInfoByPortRouter, PassengerTypeCalculatorValues, PassengerTypeCalculator}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import spray.http.DateTime
import spray.routing.Directives

import scala.concurrent.Await
import scala.concurrent.duration._

object PassengerInfoBatchComplete

import java.lang.System.nanoTime

trait SimpleProfiler {
  def profile[R](code: => R, t: Long = nanoTime) = (code, nanoTime - t)
}


class FlightPassengerSplitsPerformanceSpec extends
  TestKit(ActorSystem("FlightPassengerSplitsPerformanceSpec", ConfigFactory.empty()))
  with SpecificationLike with AfterAll with Directives
  with ImplicitSender
  with SimpleProfiler {

  test =>
  //these exist as a mechanism for manually exploring the performance profiles of different implementations of
  //the splits calculator layout. we have them  skipAll so they don't run in the ci pipeline.

//  skipAll
  sequential

  def actorRefFactory = system


  implicit val timeout = akka.util.Timeout(40, SECONDS)

  val log = Logging(system, classOf[FlightPassengerSplitsPerformanceSpec])

  def airportGen = Gen.oneOf("LTN", "STN", "LHR", "GTW", "EDI")

  def carrierCodeGen = Gen.oneOf("EZ", "BA", "RY", "NZ")

  def voyageNumberGen = Gen.chooseNum(1000, 9999)

  def eventType = Gen.oneOf("DC", "CI")

  def passengerInfoGen: Gen[PassengerInfoJson] = for {
    dt <- Gen.oneOf("P", "V")
    dicc <- Gen.oneOf(PassengerTypeCalculatorValues.EEACountries.toSeq)
    eeaFlag = "EEA"
    age <- Gen.chooseNum(1, 99)
    disembarkation <- Gen.oneOf(Some("LHR"), Some("COL"))
    inTransit <- Gen.oneOf("Y", "N")
    disCc <- Gen.oneOf(Some("GBR"), Some("MNP"))
    natCc <- Gen.oneOf(Some("GBR"), None)
  } yield PassengerInfoJson(Some(dt), dicc, eeaFlag, Some(age.toString), DisembarkationPortCode = disembarkation,
    InTransitFlag = inTransit,
    DisembarkationPortCountryCode = disCc,
    NationalityCountryCode = natCc)

  // todo figure out scala check Gen.parameters
  def flightGen(dateTime: DateTime): Gen[VoyageManifest] = for {
    et <- eventType
    port <- airportGen
    departurePort <- airportGen
    carrier <- carrierCodeGen
    vn <- voyageNumberGen
    minute <- Gen.chooseNum(0, 59)
    hour <- Gen.chooseNum(0, 23)
    randomizedDateTime = dateTime.copy(hour = hour, minute = minute)
    dateStr = randomizedDateTime.toIsoLikeDateTimeString.split(" ")
    passengers <- Gen.listOf(passengerInfoGen)
  } yield VoyageManifest(et, port, departurePort, vn.toString, carrier,
    dateStr(0),
    dateStr(1), passengers)

  case class FlightId(port: String, carrier: String, voyageNumber: String, scheduledArrival: DateTime)

  def flightIdGen(dateTime: DateTime): Gen[FlightId] = for {
    port <- airportGen
    carrier <- carrierCodeGen
    vn <- voyageNumberGen
    minute <- Gen.chooseNum(0, 59)
    hour <- Gen.chooseNum(0, 23)
    randomizedDateTime = dateTime.copy(hour = hour, minute = minute)
  } yield FlightId(port, vn.toString, carrier, randomizedDateTime)


  def flightStream(startDateTime: DateTime): Stream[VoyageManifest] = {
    Arbitrary(flightGen(startDateTime)).arbitrary.sample.get #::
      flightStream(startDateTime)
  }

  def flightList(startDateTime: DateTime, numFlights: Int): Seq[VoyageManifest] = {
    (0 to numFlights).flatMap(n => Arbitrary(flightGen(startDateTime)).arbitrary.sample.get :: Nil)
  }

//  val aggregationRef: ActorRef = system.actorOf(Props[PassengerSplitsInfoByPortRouter])
  val aggregationRef: ActorRef = system.actorOf(Props[FlatPassengerSplitsInfoByPortRouter])

  "Given lots of flight events" >> {
    tag("performance")

    val totalEvents: Int = 100

    s"looking for the first event " in {
      val flightsToFind: Vector[VoyageManifest] = initialiseFlightsWithStream(aggregationRef, totalEvents)

      val flightToFind = flightsToFind.take(1).toList.head
      log.info(s"Looking for ${flightToFind}")
      findFlightAndCheckResult(flightToFind)
      success("yay")
    }
    s"looking for multiple events" in {
      val flightsToFind: Vector[VoyageManifest] = initialiseFlightsWithStream(aggregationRef, totalEvents)

      val (results, totalTime) = profile {
        flightsToFind foreach {
          flightToFind =>
            val (result, time) = profile {
              findFlightAndCheckResult(flightToFind)
            }
            log.info(s"Find of ${flightToFind.summary} took ${time / 1000000}")
            result
        }
      }
      log.info(s"looking for multiple (${flightsToFind.length}) events in sequence total time ${totalTime / 1e9}")
      success("yay ")
    }
  }

  def findFlightAndCheckResult(flightToFind: VoyageManifest): Unit = {
    flightToFind match {
      case VoyageManifest(_, port, originPort, voyageNumber, carrier, scheduleDate, scheduledTime, passengers) =>
        val nearlyIsoArrivalDt = s"${scheduleDate.replace("-", "")}T${scheduledTime.replace(":", "").take(4)}"
        nearlyIsoArrivalDt

        val askableRef: AskableActorRef = aggregationRef
        val resultFuture = askableRef ? ReportVoyagePaxSplit(port, carrier + "ignore", voyageNumber, flightToFind.scheduleArrivalDateTime.get)
        val result = Await.ready(resultFuture, 10 seconds)
        log.info(s"result was ${result}")
      case default =>
        log.error("Why are we here?")
        throw new Exception("fail")
      //        failTest(s"Why are we here? ${default}")
    }
  }

  val millisPerDay = 1000 * 60 * 60 * 24

  def dateStream(sd: DateTime): Stream[DateTime] = sd #:: dateStream(sd + millisPerDay)

  def initialiseFlightsWithStream(aggRef: ActorRef, totalEvents: Int): Vector[VoyageManifest] = {
    println("Initialise flights")
    val (result, time) = profile {
      val dts = dateStream(DateTime(2016, 4, 1))
      val flightsPerDay: Int = 1400
      val numberOfDays: Int = 30

      val fs: Stream[(DateTime, Seq[VoyageManifest])] = dts.take(numberOfDays)
        .map((currentDay) => (currentDay, flightList(currentDay, flightsPerDay)))
      //        .take(totalEvents)
      fs.map {
        (args) =>
          val (currentDay, dayOfEvents: Seq[VoyageManifest]) = args
          val groupList: List[VoyageManifest] = dayOfEvents.toList
          val batchActor = system.actorOf(Props(new PassengerInfoBatchActor(testActor, aggregationRef, groupList, currentDay.toString())), s"flightprocess-batch-$currentDay")
          log.info(s"Sending ${dayOfEvents.length} messages for ${currentDay}")
          batchActor ! "Begin"
          expectMsg(500 seconds, PassengerInfoBatchComplete)

          log.info("Sent all messages and they're processed")
          groupList.take(100)
      }.flatten.toVector
    }
    log.info(s"Initialise took ${time / 1000000}")
    result

  }


  def afterAll() = system.terminate()
}


