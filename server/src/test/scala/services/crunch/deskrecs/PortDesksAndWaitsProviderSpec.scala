package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch, PassengersMinute}
import drt.shared.{ArrivalGenerator, CrunchApi, TQM}
import services.crunch.CrunchTestLike
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter}
import services.{OptimiserWithFlexibleProcessors, WorkloadProcessors, WorkloadProcessorsProvider}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Splits}
import uk.gov.homeoffice.drt.egates.Desk
import uk.gov.homeoffice.drt.ports.PaxTypes.GBRNational
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, LiveFeedSource, PaxTypeAndQueue, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, MilliTimes, SDate, SDateLike}

import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class PortDesksAndWaitsProviderSpec extends CrunchTestLike {
  val gbrToDesk = 18
  val gbrToEgate = 20

  "A PortDesksAndWaitsProvider should produce correct LoadMinutes" >> {
    "Given a single flight with 2 passengers and a single split" >> {
      val scheduled = SDate("2022-08-11T12:00")
      val pax = 2
      val flights = List(
        (pax, Set(ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, pax, None, None)))
      )
      val loads = getFlightLoads(scheduled, flights, getProvider)
      val scheduledMillis = scheduled.millisSinceEpoch

      val expected = Map(TQM(T1, EeaDesk, scheduledMillis) -> PassengersMinute(T1, EeaDesk, scheduledMillis, List(gbrToDesk, gbrToDesk), None))

      loads === expected
    }
    "Given two flights with 1 & 2 passengers and single splits" >> {
      val scheduled = SDate("2022-08-11T12:00")
      val pax1 = 1
      val pax2 = 2
      val flights = List(
        (pax1, Set(ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, pax1, None, None))),
        (pax2, Set(ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, pax2, None, None))),
      )
      val loads = getFlightLoads(scheduled, flights, getProvider)
      val scheduledMillis = scheduled.millisSinceEpoch

      val expected = Map(TQM(T1, EeaDesk, scheduledMillis) -> PassengersMinute(T1, EeaDesk, scheduledMillis, List(gbrToDesk, gbrToDesk, gbrToDesk), None))

      loads === expected
    }
    "Given a flight with 2 passengers and 2 equal splits" >> {
      val scheduled = SDate("2022-08-11T12:00")
      val pax = 2
      val flights = List(
        (pax, Set(
          ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, pax.toDouble / 2, None, None),
          ApiPaxTypeAndQueueCount(GBRNational, EGate, pax.toDouble / 2, None, None),
        )),
      )
      val loads = getFlightLoads(scheduled, flights, getProvider)
      val scheduledMillis = scheduled.millisSinceEpoch

      val expected = Map(
        TQM(T1, EeaDesk, scheduledMillis) -> PassengersMinute(T1, EeaDesk, scheduledMillis, List(gbrToDesk), None),
        TQM(T1, EGate, scheduledMillis) -> PassengersMinute(T1, EGate, scheduledMillis, List(gbrToEgate), None),
      )

      loads === expected
    }
  }
  "A PortDesksAndWaitsProvider should produce correct DeskRecs" >> {
    "Given a single flight with 2 passengers and a single split" >> {
      val scheduled = SDate("2022-08-11T12:00")
      val pax = 2
      val flights = List((pax, Set(ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, pax, None, None))))
      val loads = Await.result(getDeskRecs(scheduled, flights, getProvider), 1.second).minutes
        .filter(m => m.queue == EeaDesk && m.minute == scheduled.millisSinceEpoch)

      val expected = List(
        DeskRecMinute(T1, EeaDesk, 1660219200000L, pax, pax * gbrToDesk, 10, 0, Some(2)),
      )

      loads === expected
    }
  }

  object MockTerminalDeskLimits extends TerminalDeskLimitsLike {
    override val minDesksByQueue24Hrs: Map[Queue, IndexedSeq[Int]] = Map(
      EeaDesk -> IndexedSeq.fill(24)(10),
      EGate -> IndexedSeq.fill(24)(10),
      NonEeaDesk -> IndexedSeq.fill(24)(10),
    )

    override def maxDesksForMinutes(minuteMillis: NumericRange[MillisSinceEpoch], queue: Queue, existingAllocations: Map[Queue, List[Int]]): Future[WorkloadProcessorsProvider] =
      Future.successful(WorkloadProcessorsProvider(DeskRecs.desksForMillis(minuteMillis, IndexedSeq.fill(24)(10)).map(x => WorkloadProcessors(Seq.fill(x)(Desk)))))
  }

  private def getDeskRecs(scheduled: SDateLike, flightParams: List[(Int, Set[ApiPaxTypeAndQueueCount])], provider: PortDesksAndWaitsProvider): Future[CrunchApi.DeskRecMinutes] = {
    val start = scheduled.millisSinceEpoch
    val end = scheduled.addMinutes(14).millisSinceEpoch
    val loads = getFlightLoads(scheduled, flightParams, provider)
    provider.terminalLoadsToDesks(
      minuteMillis = start to end by MilliTimes.oneMinuteMillis,
      loads,
      Map(T1 -> MockTerminalDeskLimits),
      "test"
    )
  }

  private def getFlightLoads(scheduled: SDateLike,
                             flightParams: List[(Int, Set[ApiPaxTypeAndQueueCount])],
                             provider: PortDesksAndWaitsProvider): Map[TQM, PassengersMinute] = {
    val start = scheduled.millisSinceEpoch
    val end = scheduled.addMinutes(14).millisSinceEpoch
    val flights = flightParams.zipWithIndex.map {
      case ((pax, splits), idx) =>
        val arrival = ArrivalGenerator.arrival(iata = s"BA${idx.toString}",
          origin = PortCode(idx.toString),
          terminal = T1, sch = scheduled.millisSinceEpoch,
          totalPax = Option(pax),
        ).toArrival(LiveFeedSource).copy(PcpTime = Option(scheduled.millisSinceEpoch))
        ApiFlightWithSplits(
          arrival,
          Set(Splits(splits, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, None))
        )
    }

    provider.flightsToLoads(
      minuteMillis = start to end by MilliTimes.oneMinuteMillis,
      flights = FlightsWithSplits(flights),
      RedListUpdates.empty,
      _ => (_: Queue, _: MillisSinceEpoch) => Open,
      _ => None,
    )
  }


  private def getProvider = {
    val queues = SortedMap[Terminal, Seq[Queue]](T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
    val divertedQueues = Map[Queue, Queue]()
    val terminalDesks = Map[Terminal, Int](T1 -> 10)
    val flexedQueuesPriority = List(EeaDesk, EGate, NonEeaDesk)
    val slas = (_: LocalDate, queue: Queue) => Future.successful(Map[Queue, Int](EeaDesk -> 25, EGate -> 20, NonEeaDesk -> 45)(queue))
    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(
      PaxTypeAndQueue(GBRNational, EeaDesk) -> 18d,
      PaxTypeAndQueue(GBRNational, EGate) -> 20d,
    ))
    val minutesToCrunch = 3
    val offsetMinutes = 0
    val tryCrunch = OptimiserWithFlexibleProcessors.crunchWholePax _
    val workLoadCalc = DynamicWorkloadCalculator(procTimes, QueueFallbacks(Map()), FlightFilter(List()), 45, paxFeedSourceOrder)

    PortDesksAndWaitsProvider(queues, divertedQueues, terminalDesks, flexedQueuesPriority, slas,
      procTimes, minutesToCrunch, offsetMinutes, tryCrunch, workLoadCalc, paxFeedSourceOrder)
  }
}
