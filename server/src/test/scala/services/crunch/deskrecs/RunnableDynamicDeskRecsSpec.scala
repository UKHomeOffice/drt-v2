package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.ClosedShape
import akka.stream.scaladsl.GraphDSL.Implicits
import akka.stream.scaladsl.GraphDSL.Implicits.port2flow
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared._
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest, VoyageManifests}
import queueus.{AdjustmentsNoop, B5JPlusTypeAllocator, PaxTypeQueueAllocation, TerminalQueueAllocator}
import services.crunch.VoyageManifestGenerator.{euPassport, visa}
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.NewRunnableDeskRecs.{daysToDeskRecs, newGraph}
import services.crunch.deskrecs.RunnableDynamicDeskRecs.log
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import services.graphstages.CrunchMocks
import services.{SDate, TryCrunch}

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class MockActor(somethingToReturn: List[Any]) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Source(somethingToReturn)
  }
}

class MockSinkActor(probe: ActorRef) extends Actor {
  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! Ack
    case somethingToReturn =>
      probe ! somethingToReturn
      sender() ! Ack
  }
}

case class CrunchStuff(startTime: MillisSinceEpoch, flights: FlightsWithSplits, manifests: VoyageManifests)

class RunnableDynamicDeskRecsSpec extends CrunchTestLike {

  "Given a stream of days and a flights provider and a manifests provider" >> {
    val date = "2021-06-01"
    val time = "12:00"
    val scheduled = s"${date}T$time"

    val flights: FlightsWithSplits = FlightsWithSplits(Seq(ApiFlightWithSplits(
      ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = scheduled, origin = PortCode("JFK")), Set())))
    val liveManifests: VoyageManifests = VoyageManifests(Set(
      VoyageManifestGenerator.voyageManifest(paxInfos = List(euPassport), scheduledDate = ManifestDateOfArrival(date), scheduledTime = ManifestTimeOfArrival(time), voyageNumber = VoyageNumber(1))))
    val historicManifest = VoyageManifestGenerator.voyageManifest(paxInfos = List(visa))

    val flightsProvider = (_: MillisSinceEpoch) => Future(Source(List(flights)))
    val liveManifestsProvider = (_: MillisSinceEpoch) => Future(Source(List(liveManifests)))
    val historicManifestsProvider = (arrivals: Iterable[Arrival]) =>
      Future(arrivals.map(arrival => (ArrivalKey(arrival), historicManifest)).toMap)

    val dayMillis = SDate(scheduled).millisSinceEpoch
    val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(defaultAirportConfig)
    val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
    val pcpPaxCalcFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

    val ptqa = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator(),
      TerminalQueueAllocator(defaultAirportConfig.terminalPaxTypeQueueAllocation))
    val splitsCalculator = manifests.queues.SplitsCalculator(ptqa, defaultAirportConfig.terminalPaxSplits, AdjustmentsNoop())

    val desksAndWaitsProvider: DesksAndWaitsPortProvider = DesksAndWaitsPortProvider(defaultAirportConfig, mockCrunch, pcpPaxCalcFn)

    "When I create a runnable graph from a processor that adds flights and manifests" >> {
      val daysSourceQueue = Source(List(dayMillis))
      val crunchPeriodStartMillis: SDateLike => SDateLike = day => day
      val probe = TestProbe()
      val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

      val deskRecs = daysToDeskRecs(
        flightsProvider,
        liveManifestsProvider,
        historicManifestsProvider,
        crunchPeriodStartMillis,
        splitsCalculator,
        desksAndWaitsProvider,
        maxDesksProvider) _

      val graph = newGraph(sink, daysSourceQueue, deskRecs)

      graph.run()

      probe.fishForMessage(1 second) {
        case DeskRecMinutes(drms) =>
          val tqPax = drms
            .groupBy(drm => (drm.terminal, drm.queue))
            .map {
              case (tq, minutes) => (tq, minutes.map(_.paxLoad).sum)
            }
          println(s"got $tqPax")

          false
      }

      success
    }
  }
}

object NewRunnableDeskRecs {
  def newGraph[OUT](deskRecsSinkActor: ActorRef,
                    daysSourceQueue: Source[MillisSinceEpoch, NotUsed],
                    daysToDeskRecs: DaysToDeskRecs)
                   (implicit ec: ExecutionContext): RunnableGraph[NotUsed] = {
    val graph = GraphDSL.create(daysSourceQueue) {
      implicit builder =>
        daysSourceQueueAsync =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysToDeskRecs(daysSourceQueueAsync.out) ~> deskRecsSink

          ClosedShape

    }

    RunnableGraph.fromGraph(graph)
  }

  type DaysToDeskRecs = Implicits.PortOps[MillisSinceEpoch] => Implicits.PortOps[DeskRecMinutes]

  def daysToDeskRecs(flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]],
                     liveManifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]],
                     historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                     crunchStart: SDateLike => SDateLike,
                     splitsCalculator: SplitsCalculator,
                     portDeskRecs: DesksAndWaitsPortProviderLike,
                     maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
                    (days: Implicits.PortOps[MillisSinceEpoch])
                    (implicit ec: ExecutionContext): Implicits.PortOps[DeskRecMinutes] = {
    val crunchDays = days.map(millis => crunchStart(SDate(millis)).millisSinceEpoch)
    val withFlights = addFlights(crunchDays, flightsProvider)
    val withSplits = addSplits(withFlights, liveManifestsProvider, historicManifestsProvider, splitsCalculator)
    addDeskRecs(withSplits, portDeskRecs, maxDesksProviders)
  }

  def addDeskRecs(dayAndFlights: Implicits.PortOps[(MillisSinceEpoch, Iterable[ApiFlightWithSplits])],
                  portDeskRecs: DesksAndWaitsPortProviderLike,
                  maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike]
                 ): Implicits.PortOps[DeskRecMinutes] = {
    dayAndFlights
      .map { case (crunchStartMillis, flights) =>
        val crunchEndMillis = SDate(crunchStartMillis).addMinutes(portDeskRecs.minutesToCrunch).millisSinceEpoch
        val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

        log.info(s"Crunching ${flights.size} flights, ${minuteMillis.length} minutes (${SDate(crunchStartMillis).toISOString()} to ${SDate(crunchEndMillis).toISOString()})")
        val startTime = System.currentTimeMillis()
        val loads = portDeskRecs.flightsToLoads(FlightsWithSplits(flights), crunchStartMillis)
        println(s"loads: $loads")
        val minutes = portDeskRecs.loadsToDesks(minuteMillis, loads, maxDesksProviders)
        val timeTaken = System.currentTimeMillis() - startTime
        if (timeTaken > 1000) {
          log.warn(s"Simulation took ${timeTaken}ms")
        }
        minutes
      }
  }

  def addFlights(days: Implicits.PortOps[MillisSinceEpoch],
                 flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]])
                (implicit ec: ExecutionContext): Implicits.PortOps[(MillisSinceEpoch, FlightsWithSplits)] =
    days
      .mapAsync(1) { day =>
        flightsProvider(day).map(flightsStream => (day, flightsStream))
      }
      .flatMapConcat { case (day, flights) =>
        flights.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (day, fws))
      }

  def addSplits(dayWithFlights: Implicits.PortOps[(MillisSinceEpoch, FlightsWithSplits)],
                liveManifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]],
                historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                splitsCalculator: SplitsCalculator)
               (implicit ec: ExecutionContext): Implicits.PortOps[(MillisSinceEpoch, Iterable[ApiFlightWithSplits])] =
    dayWithFlights
      .mapAsync(1) { case (day, flightsSource) =>
        liveManifestsProvider(day).map(manifestsStream => (day, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (day, fws, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(vms => (day, fws, vms))
      }
      .map { case (day, fws, vms) =>
        (day, addLiveManifests(fws, vms, splitsCalculator))
      }
      .mapAsync(1) {
        case (day, flights) =>
          val toLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)

          historicManifestsProvider(toLookup).map { manifests =>
            (day, addHistoricManifests(flights, manifests, splitsCalculator))
          }
      }

  def addHistoricManifests(flights: Iterable[ApiFlightWithSplits],
                           manifests: Map[ArrivalKey, VoyageManifest],
                           splitsCalculator: SplitsCalculator): Iterable[ApiFlightWithSplits] =
    flights.map { flight =>
      val x = if (flight.splits.nonEmpty) {
        println(s"live splits: ${flight.splits}")
        flight
      }
      else {
        val splits = manifests.get(ArrivalKey(flight.apiFlight)) match {
          case Some(historicManifest) =>
            val splits1 = splitsCalculator.bestSplitsForArrival(historicManifest, flight.apiFlight)
            println(s"historic manifest: $historicManifest")
            println(s"historic splits: $splits1")
            splits1
          case None =>
            val splits1 = splitsCalculator.terminalDefaultSplits(flight.apiFlight.Terminal)
            println(s"terminal default splits: $splits1")
            splits1
        }
        ApiFlightWithSplits(flight.apiFlight, Set(splits))
      }
      println(s"flight: $x")
      println(s"best splits: ${x.bestSplits}")
      x
    }

  def addLiveManifests(fws: FlightsWithSplits,
                       vms: VoyageManifests,
                       splitsCalculator: SplitsCalculator): Iterable[ApiFlightWithSplits] = {
    val liveManifests = vms.manifests.map(vm => vm.maybeKey.map(key => (key, vm))).collect {
      case Some((key, vm)) => (key, vm)
    }.toMap

    fws.flights.values.map {
      case ApiFlightWithSplits(arrival, _, _) =>
        val splits = liveManifests.get(ArrivalKey(arrival)) match {
          case Some(liveManifest) =>
            println(s"Found live manifest")
            Some(splitsCalculator.bestSplitsForArrival(liveManifest, arrival))
          case None =>
            println(s"Live manifest not found for ${ArrivalKey(arrival)}. $liveManifests")
            None
        }
        ApiFlightWithSplits(arrival, splits.toSet)
    }
  }
}

// TQM(T1,EeaDesk,1622549040000) -> LoadMinute(T1,EeaDesk,4.0,1.6666666666666667,1622549040000),
// TQM(T1,EGate,1622549040000) -> LoadMinute(T1,EGate,16.0,0.0,1622549040000),
// TQM(T1,EeaDesk,1622548920000) -> LoadMinute(T1,EeaDesk,4.0,1.6666666666666667,1622548920000),
// TQM(T1,EGate,1622548860000) -> LoadMinute(T1,EGate,16.0,0.0,1622548860000),
// TQM(T1,EeaDesk,1622548860000) -> LoadMinute(T1,EeaDesk,4.0,1.6666666666666667,1622548860000),
// TQM(T1,EeaDesk,1622548980000) -> LoadMinute(T1,EeaDesk,4.0,1.6666666666666667,1622548980000),
// TQM(T1,EeaDesk,1622548800000) -> LoadMinute(T1,EeaDesk,4.0,1.6666666666666667,1622548800000),
// TQM(T1,EGate,1622548980000) -> LoadMinute(T1,EGate,16.0,0.0,1622548980000),
// TQM(T1,EGate,1622548800000) -> LoadMinute(T1,EGate,16.0,0.0,1622548800000),
// TQM(T1,EGate,1622548920000) -> LoadMinute(T1,EGate,16.0,0.0,1622548920000))
