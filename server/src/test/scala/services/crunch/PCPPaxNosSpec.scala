package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class PCPPaxNosSpec extends CrunchTestLike {
  sequential
  isolated

  override def useApiPaxNos = false

  val tenMinutes: Double = 600d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaChildToDesk -> tenMinutes))

  val scheduled = "2019-11-20T00:00Z"

  val flights = Flights(List(
    ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(1), origin = PortCode("JFK"))
  ))

  val manifests =
    ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.DC, defaultAirportConfig.portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2019-11-20"), ManifestTimeOfArrival("00:00"),
        List(
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(11)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None),
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(11)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
        ))
    )))

  "Given the API Feature flag is turned off then we should use the live feed passenger numbers" >> {

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = procTimes,
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
      ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))
    offerAndWait(crunch.manifestsLiveInput, manifests)

    val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 15)

        println(s"results::: $resultSummary")

        resultSummary == expected
    }

    success
  }

}
