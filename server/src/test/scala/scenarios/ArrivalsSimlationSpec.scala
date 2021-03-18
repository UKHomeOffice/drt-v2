package scenarios

import actors.GetState
import akka.actor.Props
import akka.pattern.ask
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.airportconfig.Lhr
import drt.shared.api.Arrival
import manifests.queues.SplitsCalculator
import queueus.{AdjustmentsNoop, B5JPlusTypeAllocator, PaxTypeQueueAllocation, TerminalQueueAllocator}
import services.crunch.CrunchTestLike
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.OptimiserMocks.{mockHistoricManifestsProviderNoop, mockLiveManifestsProviderNoop}
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.deskrecs._
import services.exports.StreamingFlightsExport
import services.imports.{ArrivalCrunchSimulationActor, ArrivalImporter}
import services.{Optimiser, SDate}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ArrivalsSimlationSpec extends CrunchTestLike {

  val csv: String =
    """|IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track,API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates
       |TST100,TST100,SIN,/535,On Chocks,2020-06-17,05:50,05:38,05:38,05:45,05:45,05:53,30,14,,,,,9,4,1,,7,2,5,0,0.0,0.0,8.0,7.0,1.0,1.0,13.0,46.0"""
      .stripMargin

  val terminal: Terminal = Terminal("T5")

  def fwsToCsv(flights: Seq[ApiFlightWithSplits]): String =
    StreamingFlightsExport(
      (millis: MillisSinceEpoch) => SDate(millis).toISODateOnly,
      (millis: MillisSinceEpoch) => SDate(millis).toHoursAndMinutes
    ).toCsvWithActualApi(List(FlightsWithSplits(flights)))


  "Given a CSV with all the columns we need in it then we should get a flight with splits" >> {

    val result1: Array[ApiFlightWithSplits] = ArrivalImporter(csv, terminal)

    val csv2 = fwsToCsv(result1)

    val result2 = ArrivalImporter(csv2, terminal)

    result1.head === result2.head
  }


  "Given an APIFlightWithSplits then I should be able to convert it into a CSV and back to the same APIFlightWithSplits" >> {
    val flight: Arrival = ArrivalGenerator.arrival(
      iata = "TST100",
      actPax = Option(200),
      tranPax = Option(0),
      schDt = "2020-06-17T05:30:00Z",
      terminal = terminal,
      airportId = PortCode("ID"),
      status = ArrivalStatus("Scheduled"),
      feedSources = Set(LiveFeedSource),
      pcpDt = "2020-06-17T06:30:00Z"
    )
    val splits = Splits(Set(
      ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 1.0, None, None),
      ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 2.0, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 3.0, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 4.0, None, None),
      ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 5.0, None, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 6.0, None, None),
      ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 7.0, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 8.0, None, None)
    ), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val expected: ApiFlightWithSplits = ApiFlightWithSplits(flight, Set(splits))

    val csvFromFlightWithSplits = fwsToCsv(Seq(expected))
    val result: ApiFlightWithSplits = ArrivalImporter(csvFromFlightWithSplits, terminal).head

    result === expected
  }

  "Given an arrival CSV row then I should get back a representative ApiPaxTypeAndQueueCount split for the arrival" >> {

    val csvLines = ArrivalImporter.toLines(csv)
    val headers = ArrivalImporter.csvHeadings(csvLines)

    val result = ArrivalImporter.lineToSplits(ArrivalImporter.lineToFields(csvLines(1)), headers)

    val expected: Set[Splits] = Set(Splits(
      Set(
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 0, None, None),
        ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 0, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 8.0, None, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 7.0, None, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 1.0, None, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 1.0, None, None),
        ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 13.0, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 46.0, None, None)
      ),
      ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC)
    ))

    result === expected
  }

  "Given a csv of arrivals for a day then I should get a desks and queues export for that day" >> {
    val flightsWithSplits = ArrivalImporter(csv, terminal)

    val lhrHalved = Lhr.config.copy(
      minMaxDesksByTerminalQueue24Hrs = Lhr.config.minMaxDesksByTerminalQueue24Hrs.mapValues(_.map {
        case (q, (_, max)) =>
          val openDesks = max.map(x => x / 2)
          q -> (openDesks, openDesks)
      }),
      eGateBankSize = 5,
      slaByQueue = Lhr.config.slaByQueue.mapValues(_ => 15)
    )
    val fws = FlightsWithSplits(flightsWithSplits.map(f => f.unique -> f).toMap)

    val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(fws)))
    val dawp = PortDesksAndWaitsProvider(lhrHalved, Optimiser.crunch)

    val terminalDeskLimits = PortDeskLimits.fixed(lhrHalved)

    val paxAllocation = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      TerminalQueueAllocator(lhrHalved.terminalPaxTypeQueueAllocation))

    val splitsCalc = SplitsCalculator(paxAllocation, lhrHalved.terminalPaxSplits, AdjustmentsNoop)

    val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
      OptimisationProviders.arrivalsProvider(portStateActor),
      mockLiveManifestsProviderNoop,
      mockHistoricManifestsProviderNoop,
      splitsCalc,
      system.actorOf(Props(new MockSplitsSinkActor)),
      dawp.flightsToLoads,
      dawp.loadsToDesks,
      terminalDeskLimits
    )

    val (crunchRequestQueue, deskRecsKillSwitch) = RunnableOptimisation.createGraph(portStateActor, deskRecsProducer).run()

    val date = SDate("2020-06-17T05:30:00Z")
    crunchRequestQueue.offer(CrunchRequest(date.millisSinceEpoch, lhrHalved.crunchOffsetMinutes, lhrHalved.minutesToCrunch))

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => drm
    }

    val deskRecMinutes = Await.result(futureDeskRecMinutes, 5 seconds)
    deskRecsKillSwitch.shutdown()

    val totalPax = deskRecMinutes.minutes.map(_.paxLoad).sum

    totalPax === 14.0
  }

}
