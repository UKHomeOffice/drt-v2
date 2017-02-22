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
import passengersplits.core.{PassengerSplitsInfoByPortRouter, PassengerTypeCalculator}
import passengersplits.parsing.PassengerInfoParser.{PassengerInfoJson, VoyagePassengerInfo}
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
  //  implicit lazy val system = ActorSystem("performanceTests", ConfigFactory.empty("test")) // may add arguments here

  def actorRefFactory = system

  sequential

  implicit val timeout = akka.util.Timeout(40, SECONDS)

  val log = Logging(system, classOf[FlightPassengerSplitsPerformanceSpec])

  def airportGen = Gen.oneOf("LTN", "STN", "LHR", "GTW", "EDI")

  def carrierCodeGen = Gen.oneOf("EZ", "BA", "RY", "NZ")

  def voyageNumberGen = Gen.chooseNum(1000, 9999)

  def eventType = Gen.oneOf("DC", "CI")

  def passengerInfoGen: Gen[PassengerInfoJson] = for {
    dt <- Gen.oneOf("P", "V")
    dicc <- Gen.oneOf(PassengerTypeCalculator.EEACountries.toSeq)
    eeaFlag = "EEA"
    age <- Gen.chooseNum(1, 99)
  } yield PassengerInfoJson(Some(dt), dicc, eeaFlag, Some(age.toString))

  // todo figure out scala check Gen.parameters
  def flightGen(dateTime: DateTime): Gen[VoyagePassengerInfo] = for {
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
  } yield VoyagePassengerInfo(et, port, departurePort, vn.toString, carrier,
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


  //  def flights(max: Int) = (1 to max).map {
  //    (n) => {
  //      val dateTime = DateTime(2016, 4, 1, 12, 23)
  //      val v: IndexedSeq[VoyagePassengerInfo] = Arbitrary(flightGen(dateTime)).arbitrary.sample.get
  //      v
  //    }
  //  }

  def flightStream(startDateTime: DateTime): Stream[VoyagePassengerInfo] = {
    Arbitrary(flightGen(startDateTime)).arbitrary.sample.get #::
      flightStream(startDateTime)
  }

  def flightList(startDateTime: DateTime, numFlights: Int): Seq[VoyagePassengerInfo] = {
    (0 to numFlights).flatMap(n => eventsFromFlight(Arbitrary(flightGen(startDateTime)).arbitrary.sample.get).reverse)
  }

  def eventsFromFlight(vpi: VoyagePassengerInfo): List[VoyagePassengerInfo] = {
    vpi match {
      case v if v.PassengerList == Nil => Nil
      case v =>
        val copy = v.copy(PassengerList = v.PassengerList.tail)
        copy :: eventsFromFlight(copy)
    }
  }

  val aggregationRef: ActorRef = system.actorOf(Props[PassengerSplitsInfoByPortRouter])

  "Given lots of flight events" >> {
    tag("performance")
    val totalEvents: Int = 100
    val flightsToFind: List[VoyagePassengerInfo] = initialiseFlightsWithStream(aggregationRef, totalEvents)

    s"looking for the first event " in {
      val flightToFind = flightsToFind.take(1).toList.head
      log.info(s"Looking for ${flightToFind}")
      findFlightAndCheckResult(flightToFind)
      success("yay")
    }
    s"looking for multiple events ${flightsToFind.length}" in {
      val (results, time) = profile {
        flightsToFind foreach {
          flightToFind =>
            val (result, time) = profile {
              findFlightAndCheckResult(flightToFind)
            }
            log.info(s"Find of ${flightToFind.summary} took ${time / 1000000}")
            result
        }
      }
      log.info(s"looking for multiple events in sequence total time ${time}")
      success("yay ")
    }
  }

  def findFlightAndCheckResult(flightToFind: VoyagePassengerInfo): Unit = {
    flightToFind match {
      case VoyagePassengerInfo(_, port, originPort, voyageNumber, carrier, scheduleDate, scheduledTime, passengers) =>
        val nearlyIsoArrivalDt = s"${scheduleDate.replace("-", "")}T${scheduledTime.replace(":", "").take(4)}"
        nearlyIsoArrivalDt

        val askableRef: AskableActorRef = aggregationRef
        val resultFuture = askableRef ? ReportVoyagePaxSplit(port, carrier+"ignore", voyageNumber, flightToFind.scheduleArrivalDateTime.get)
        val result = Await.ready(resultFuture, 10 seconds)
        println(s"Result for $flightToFind $result")
      //        val routeToRequest: String = s"/flight-pax-splits/dest-${port}/terminal-N/${carrier}${voyageNumber}/scheduled-arrival-time-${nearlyIsoArrivalDt}"
      //        log.info(s"About to request ${routeToRequest}")
      //        Get(routeToRequest) ~>
      //          serviceAgg.route ~> check {
      //          log.info("response was: " + responseAs[String])
      //          assert(response.status === StatusCodes.OK, s"Couldn't find ${port},${carrier}${voyageNumber}@${nearlyIsoArrivalDt}")
      //          val json: JsValue = responseAs[String].parseJson
      //          json match {
      //            case JsArray(elements) =>
      //              val head1 = elements.head.asJsObject
      //              head1.getFields("destinationPort", "carrierCode", "voyageNumber", "scheduledArrivalDateTime") match {
      //                case Seq(JsString(port), JsString(carrier), JsString(voyageNumber), JsString(isoArrivalDt)) =>
      //                  success("we got what we came for")
      //                case otherwise =>
      //                  failure(otherwise.toString())
      //              }
      //
      //              log.info(s"head is ${head1}")
      //            case _ => failTest("response was not an array")
      //          }
      //        }
      case default =>
        log.error("Why are we here?")
        throw new Exception("fail")
      //        failTest(s"Why are we here? ${default}")
    }
  }

  val millisPerDay = 1000 * 60 * 60 * 24

  def dateStream(sd: DateTime): Stream[DateTime] = sd #:: dateStream(sd + millisPerDay)

  def initialiseFlightsWithStream(aggRef: ActorRef, totalEvents: Int): List[VoyagePassengerInfo] = {
    println("Initialise flights")
    val (result, time) = profile {
      val dts = dateStream(DateTime(2016, 4, 1))
      val flightsPerDay: Int = 1400
      val numberOfDays: Int = 3

      val fs: Stream[(DateTime, Seq[VoyagePassengerInfo])] = dts.take(numberOfDays)
        .map((currentDay) => (currentDay, flightList(currentDay, flightsPerDay)))
      //        .take(totalEvents)
      fs.par.map {
        (args) =>
          val (currentDay, dayOfEvents: Seq[VoyagePassengerInfo]) = args
          val groupList: List[VoyagePassengerInfo] = dayOfEvents.toList
          val batchActor = system.actorOf(Props(new PassengerInfoBatchActor(testActor, aggregationRef, groupList, currentDay.toString())))
          log.info(s"Sending messages for ${currentDay}")
          batchActor ! "Begin"
          expectMsg(500 seconds, PassengerInfoBatchComplete)

          log.info("Sent all messages and they're processed")
          groupList.take(2)
      }.flatten.toList
    }
    log.info(s"Initialise took ${time / 1000000}")
    result

  }

  //  def initialiseFlights(aggregationRef: ActorRef): IndexedSeq[VoyagePassengerInfo] = {
  //    println("Initialise flights")
  //    val (result, time) = profile {
  //      val fs = flights(400000)
  //      val batchActor = system.actorOf(Props(new PassengerInfoBatchActor(testActor, aggregationRef, fs.toList)))
  //      log.info("Sending messages")
  //      batchActor ! "Begin"
  //      expectMsg(500 seconds, PassengerInfoBatchComplete)
  //
  //      log.info("Sent all messages and they're processed")
  //      fs
  //    }
  //    log.info(s"Initialise took ${time / 1000000}")
  //    result
  //  }

  def afterAll() = system.terminate()
}


