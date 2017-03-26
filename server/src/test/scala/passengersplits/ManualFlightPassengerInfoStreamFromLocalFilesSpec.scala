package passengersplits

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterAll
import passengersplits.core.PassengerInfoRouterActor.{PassengerSplitsAck, ReportVoyagePaxSplit}
import passengersplits.core.{Core, CoreActors}
import passengersplits.polling.FilePolling
import services.SDate
import drt.shared.PassengerQueueTypes
import drt.shared.PassengerSplits.{PaxTypeAndQueueCount, VoyagePaxSplits}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

class ManualFlightPassengerInfoStreamFromLocalFilesSpec extends
  TestKit(ActorSystem("localFileReadingTest", ConfigFactory.empty())) with AfterAll with SpecificationLike with ImplicitSender with CoreActors with Core {
  test =>
  skipAll
  isolated
  ignoreMsg {
    case PassengerSplitsAck => true
  }

  def afterAll(): Unit = system.terminate()

  val zipFilePath = "/Users/lancep/clients/homeoffice/drt/spikes/advancedPassengerInfo/atmos/"
  implicit val mat = ActorMaterializer()

  val flightScheduledDateTime = SDate(2017, 2, 20, 19, 0)

  "If we stream the DQ zip files to the flightPassengerReporter" >> {
    "then we should be able to query it" in {
      "When we ask for a report of voyage pax splits then we should see pax splits of the 1 passenger in eeaDesk queue" in {
        val streamDone: Future[Done] = FilePolling.beginPolling(system.log, flightPassengerReporter, zipFilePath, Some("drt_dq_17022"), "STN")(system, mat)
        val futureAssertion = streamDone.map {
          case _ => {

            flightPassengerReporter ! ReportVoyagePaxSplit("STN", "RY", "0356", flightScheduledDateTime)

            val message = receiveOne(100 seconds)

            log.info(s"Response: ${message}")
            message match {
              case VoyagePaxSplits("STN", "RY", "0356", 131, dateTime, _) if (dateTime.millisSinceEpoch == flightScheduledDateTime.millisSinceEpoch) =>
                log.info("We got a voyage pax splits for the flight we're looking for")
                success
              case other => failure(s"did not get expected response, instead got: ${other}")
            }
          }
        }
        val myResult = Await.result(futureAssertion, 40 seconds)
        myResult
      }
    }
  }
}
