package controllers.application.exports

import akka.actor.ActorSystem
import akka.stream.Materializer
import controllers.ArrivalGenerator
import controllers.application.TestDrtModule
import drt.server.feeds.{DqManifests, ManifestsFeedSuccess}
import org.scalatestplus.play.PlaySpec
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.auth.Roles.ApiView
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AclFeedSource, PaxAge, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class FlightsExportControllerSpec extends PlaySpec {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  "FlightsExportController" should {

    "get flights for a date in " in {

      val drtSystemInterface = new TestDrtModule().provideDrtSystemInterface

      val controller = new FlightsExportController(Helpers.stubControllerComponents(), drtSystemInterface)

      val flightsRouter = drtSystemInterface.actorService.flightsRouterActor
      val arrivalT1 = ArrivalGenerator
        .arrival(iata = "BA0001", terminal = T1, schDt = "2023-11-06T05:00Z", feedSource = AclFeedSource , totalPax= Some(100), transPax = None)

      flightsRouter ! ArrivalsDiff(Seq(arrivalT1), Seq())

      def manifestForDate(date: String): VoyageManifest = {
        VoyageManifest(EventTypes.DC,
          drtSystemInterface.airportConfig.portCode,
          PortCode("JFK"),
          VoyageNumber("0001"),
          CarrierCode("BA"),
          ManifestDateOfArrival(date),
          ManifestTimeOfArrival("05:00"),
          List(
            PassengerInfoJson(Option(DocumentType("P")),
              Nationality("GBR"),
              EeaFlag("EEA"),
              Option(PaxAge(19)),
              Option(PortCode("LHR")),
              InTransit("N"),
              Option(Nationality("GBR")),
              Option(Nationality("GBR")), None),
            PassengerInfoJson(Option(DocumentType("P")),
              Nationality("GBR"),
              EeaFlag("EEA"),
              Option(PaxAge(54)),
              Option(PortCode("LHR")),
              InTransit("N"),
              Option(Nationality("GBR")),
              Option(Nationality("GBR")), None)
          ))
      }

      val creationDate = SDate("2023-11-05T12:00Z")

      val manifest = manifestForDate("2023-11-06")

      val manifestFeedSuccess = ManifestsFeedSuccess(DqManifests(0, Set(manifest)), creationDate)

      drtSystemInterface.applicationService.manifestsRouterActorReadOnly ! manifestFeedSuccess

      val result = Await.ready(controller.exportFlightsWithSplitsForDayAtPointInTimeCSV(localDateString = "2023-11-06",
        pointInTime = SDate("2023-11-06T00:00").millisSinceEpoch,
        terminalName = "T1")
        .apply(FakeRequest().withHeaders("X-Forwarded-Email" -> "test@test.com",
          "X-Forwarded-Preferred-Username" -> "test",
          "X-Forwarded-User" -> "test",
          "X-Forwarded-Groups" -> s"TEST,${ApiView.name}")), 1.second)

      status(result) mustBe OK

      val resultExpected =
        s"""IATA,ICAO,Origin,Gate/Stand,Status,Scheduled,Predicted Arrival,Est Arrival,Act Arrival,Est Chocks,Act Chocks,Minutes off scheduled,Est PCP,Capacity,Total Pax,PCP Pax,Invalid API,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
