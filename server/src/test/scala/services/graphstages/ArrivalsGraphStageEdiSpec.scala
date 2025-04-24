package services.graphstages

import org.apache.pekko.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import services.PcpArrival.pcpFrom
import services.arrivals.EdiArrivalsTerminalAdjustments
import services.crunch.{CrunchGraphInputsAndProbes, CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.ports.config.AirportConfigs
import uk.gov.homeoffice.drt.time.{MilliDate, SDate, SDateLike}

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._

class ArrivalsGraphStageEdiSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"
  val estmated = s"${date}T00:37Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")

  val terminalSplits: Splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage)

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
    queuesByTerminal = defaultAirportConfig.queuesByTerminal.view.filterKeys(_ == T1).to(SortedMap),
    useTimePredictions = true,
  )
  val defaultWalkTime = 300000L
  val pcpCalc: Arrival => MilliDate = pcpFrom(airportConfig.firstPaxOffMillis, _ => defaultWalkTime)

  val setPcpTime: Seq[Arrival] => Future[Seq[Arrival]] =
    arrivals => Future.successful(arrivals.map(arrival => arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))))

  def withPcpTime(arrival: Arrival): Arrival =
    arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))

  "An EDI Arrivals Graph Stage" should {
    val newCrunch = (live: Seq[LiveArrival], fcBase: Seq[ForecastArrival], merged: Seq[ApiFlightWithSplits]) => {
      runCrunchGraph(TestConfig(
        airportConfig = AirportConfigs.confByPort(PortCode("EDI")),
        arrivalsAdjustments = EdiArrivalsTerminalAdjustments,
        now = () => dateNow,
        setPcpTimes = setPcpTime,
        initialLiveArrivals = live,
        initialForecastBaseArrivals = fcBase,
        initialPortState = Option(PortState(merged, Seq(), Seq()))
      ))
    }

    "Reassign the terminal to A1 for an incoming arrival with A2 but with an A1 baggage belt" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
      val fcBase = ArrivalGenerator.forecast(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)

      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(), Seq())

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(fcBase)))

      assertTerminals(crunch.portStateTestProbe, List(A2))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      crunch.shutdown()

      success
    }

    "Reassign the terminal to A1 for an existing incoming arrival with A2 but with an A1 baggage belt, and remain in A1 with further ACL updates" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
      val fcBase = ArrivalGenerator.forecast(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)

      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(), Seq())

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      val updated = fcBase.copy(totalPax = Option(200))
      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(updated)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      success
    }

    "Reassign the terminal to A1 for an existing incoming arrival with A2 but with an A1 baggage belt, and remain in A1 with further live updates" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))

      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(), Seq())

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live.copy(totalPax = Option(200)))))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      success
    }

    "Reassign the terminal to A1 for an existing incoming arrival with A2 but with an A1 baggage belt, and remain in A1 with cirium updates" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))

      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(), Seq())

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Seq(live.copy(estimated = Option(SDate(scheduled).millisSinceEpoch)))))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      success
    }

    "Remove any existing duplicates in the PortState" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
      val fcBase = ArrivalGenerator.forecast(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      val mergedA1 = ApiFlightWithSplits(
        withPcpTime(ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A1, baggageReclaimId = Option("1"))
          .toArrival(AclFeedSource)),
        Set())
      val mergedA2 = ApiFlightWithSplits(
        withPcpTime(ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
          .toArrival(AclFeedSource)),
        Set())

      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(fcBase), Seq(mergedA1, mergedA2))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live)))

      assertTerminals(crunch.portStateTestProbe, List(A1))

      success
    }

    "Retain live arrival switch after ACL updates" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
      val fcBase = ArrivalGenerator.forecast(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      val mergedA1 = ApiFlightWithSplits(
        ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A1, baggageReclaimId = Option("1")).toArrival(AclFeedSource),
        Set())
      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(live), Seq(fcBase), Seq(mergedA1))

      val fcBaseNew = ArrivalGenerator.forecast(iata = "BA0001", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(fcBaseNew)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for arrival at A1") {
        case ps: PortState =>
          ps.flights.values.map(a => a.apiFlight.Terminal) == List(A1, A2)
      }

      success
    }

    "Retain previous baggage id when live update has no baggage info" in {
      val live = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"), totalPax = Option(50))
      val mergedA1 = ApiFlightWithSplits(live.toArrival(LiveFeedSource), Set())
      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(live), Seq(), Seq(mergedA1))

      val updatedLive = ArrivalGenerator.live(iata = "BA1111", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, totalPax = Option(100))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(updatedLive)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for arrival at A1") {
        case ps: PortState =>
          ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.BaggageReclaimId, a.apiFlight.PassengerSources.get(LiveFeedSource).flatMap(_.actual))) == List(("BA1111", Option("1"), Option(100)))
      }

      success
    }

    "Retain live arrival switch after ACL updates" in {
      val crunch: CrunchGraphInputsAndProbes = newCrunch(Seq(), Seq(), Seq())

      val live1a1 = ArrivalGenerator.live(iata = "BA0001", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("1"))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live1a1)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for arrival at A1") {
        case ps: PortState =>
          val tuples = ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.Terminal)).toSet
          tuples == Set(("BA0001", A1))
      }

      val acl1a1 = ArrivalGenerator.forecast(iata = "BA0011", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(acl1a1)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for arrival at A1") {
        case ps: PortState =>
          val tuples = ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.Terminal)).toSet
          tuples == Set(("BA0001", A1), ("BA0011", A2))
      }

      val live2a2 = ArrivalGenerator.live(iata = "BA0002", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("7"))
      val live3a1 = ArrivalGenerator.live(iata = "BA0003", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, baggageReclaimId = Option("2"))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(live2a2, live3a1)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for BA0001 at A1, BA0002 at A2 & BA0003 at A1") {
        case ps: PortState =>
          val tuples = ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.Terminal)).toSet
          tuples == Set(("BA0001", A1), ("BA0011", A2), ("BA0002", A2), ("BA0003", A1))
      }

      val cirium2a2 = ArrivalGenerator.live(iata = "BA0002", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, estDt = estmated)
      val cirium3a1 = ArrivalGenerator.live(iata = "BA0003", schDt = scheduled, origin = PortCode("JFK"), terminal = A2, estDt = estmated)
      offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Seq(cirium2a2, cirium3a1)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for BA0001 at A1, BA0002 at A2 & BA0003 at A1") {
        case ps: PortState =>
          val tuples = ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.Terminal)).toSet
          tuples == Set(("BA0001", A1), ("BA0011", A2), ("BA0002", A2), ("BA0003", A1))
      }

      val forecast2a2 = ArrivalGenerator.forecast(iata = "BA0002", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      val forecast3a1 = ArrivalGenerator.forecast(iata = "BA0003", schDt = scheduled, origin = PortCode("JFK"), terminal = A2)
      offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(Seq(forecast2a2, forecast3a1)))

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for BA0001 at A1, BA0002 at A2 & BA0003 at A1") {
        case ps: PortState =>
          val tuples = ps.flights.values.map(a => (a.apiFlight.flightCodeString, a.apiFlight.Terminal)).toSet
          tuples == Set(("BA0001", A1), ("BA0011", A2), ("BA0002", A2), ("BA0003", A1))
      }

      success
    }
  }

  private def assertTerminals(probe: TestProbe, terminals: List[Terminal]) = {
    probe.fishForMessage(1.seconds, s"looking for arrival at $terminals") {
      case PortState(fs, _, _) => fs.values.map(_.apiFlight.Terminal) == terminals
    }
  }
}
