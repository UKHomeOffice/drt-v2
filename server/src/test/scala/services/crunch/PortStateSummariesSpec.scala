package services.crunch

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared._
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.collection.immutable.SortedMap

class PortStateSummariesSpec extends Specification {
  "Given a port state with crunch minutes for 2 queues over 30 minutes " +
    "When I ask for a 4 period summary of 15 minutes each period " +
    "Then I should see crunch minutes for all periods with the sum of any loads, and the max of any desks or wait times" >> {
    val queues: List[Queues.Queue] = List(Queues.EeaDesk, Queues.EGate)
    val cmsList = for {
      queue <- queues
      minute <- 0 to 29
    } yield {
      CrunchMinute(T1, queue, minute.toLong * 60000, minute.toDouble, None, minute.toDouble, minute, minute, Option(minute), Option(minute), Option(minute), Option(minute))
    }

    val cmsMap = SortedMap[TQM, CrunchMinute]() ++ cmsList.map(cm => (TQM(cm), cm)).toMap
    val portState = PortState(SortedMap[UniqueArrival, ApiFlightWithSplits](), cmsMap, SortedMap[TM, StaffMinute]())

    val periods = 4
    val periodSize = 15
    val summary: Map[MillisSinceEpoch, Map[Queue, CrunchMinute]] = portState.crunchSummary(SDate(0L), periods, periodSize, T1, queues)

    val expected = Map(
      0L -> Map(
        Queues.EeaDesk -> CrunchMinute(T1, Queues.EeaDesk, 0, 105, None, 105, 14, 14, Option(14), Option(14), Option(14), Option(14)),
        Queues.EGate -> CrunchMinute(T1, Queues.EGate, 0, 105, None, 105, 14, 14, Option(14), Option(14), Option(14), Option(14))
      ),
      15L * 60000 -> Map(
        Queues.EeaDesk -> CrunchMinute(T1, Queues.EeaDesk, 15 * 60000, 330, None, 330, 29, 29, Option(29), Option(29), Option(29), Option(29)),
        Queues.EGate -> CrunchMinute(T1, Queues.EGate, 15 * 60000, 330, None, 330, 29, 29, Option(29), Option(29), Option(29), Option(29))
      ),
      30L * 60000 -> Map(
        Queues.EeaDesk -> CrunchMinute(T1, Queues.EeaDesk, 30 * 60000, 0, None, 0, 0, 0, None, None, None, None),
        Queues.EGate -> CrunchMinute(T1, Queues.EGate, 30 * 60000, 0, None, 0, 0, 0, None, None, None, None)
      ),
      45L * 60000 -> Map(
        Queues.EeaDesk -> CrunchMinute(T1, Queues.EeaDesk, 45 * 60000, 0, None, 0, 0, 0, None, None, None, None),
        Queues.EGate -> CrunchMinute(T1, Queues.EGate, 45 * 60000, 0, None, 0, 0, 0, None, None, None, None)
      )
    )

    summary === expected
  }

  "Given a port state with crunch minutes with no deployed or actual desks & wait times " +
    "When I ask for a summary  " +
    "Then I should see crunch minutes with None for all the deployed and actual desks and wait times values" >> {
    val terminal = T1
    val queues: List[Queue] = List(Queues.EeaDesk, Queues.EGate)
    val cmsList = for {
      queue <- queues
      minute <- 0 to 14
    } yield {
      CrunchMinute(terminal, queue, minute.toLong * 60000, minute.toDouble, None, minute.toDouble, minute, minute, None, None, None, None)
    }

    val cmsMap = SortedMap[TQM, CrunchMinute]() ++ cmsList.map(cm => (TQM(cm), cm)).toMap
    val portState = PortState(SortedMap[UniqueArrival, ApiFlightWithSplits](), cmsMap, SortedMap[TM, StaffMinute]())

    val periods = 1
    val periodSize = 15
    val summary: Map[MillisSinceEpoch, Map[Queue, CrunchMinute]] = portState.crunchSummary(SDate(0L), periods, periodSize, terminal, queues)

    val expected = Map(
      0L -> Map(
        Queues.EeaDesk -> CrunchMinute(terminal, Queues.EeaDesk, 0, 105, None, 105, 14, 14, None, None, None, None),
        Queues.EGate -> CrunchMinute(terminal, Queues.EGate, 0, 105, None, 105, 14, 14, None, None, None, None)
      )
    )

    summary === expected
  }

  "Given a port state with staff minutes for 2 queues over 30 minutes " +
    "When I ask for a 4 period summary of 15 minutes each period " +
    "Then I should see staff minutes for all periods, with the minimum staff number, and maximum fixed point and movements nos of the period" >> {
    val terminal = T1
    val smsList = (0 to 29).map(minute => StaffMinute(terminal, minute.toLong * 60000, minute, minute, minute))

    val smsMap = SortedMap[TM, StaffMinute]() ++ smsList.map(sm => (TM(sm), sm)).toMap
    val portState = PortState(SortedMap[UniqueArrival, ApiFlightWithSplits](), SortedMap[TQM, CrunchMinute](), smsMap)

    val periods = 4
    val periodSize = 15
    val summary: Map[MillisSinceEpoch, StaffMinute] = portState.staffSummary(SDate(0L), periods, periodSize, terminal)

    val expected = Map(
      0L -> StaffMinute(terminal, 0, 0, 14, 14),
      15L * 60000 -> StaffMinute(terminal, 15 * 60000, 15, 29, 29),
      30L * 60000 -> StaffMinute(terminal, 30 * 60000, 0, 0, 0),
      45L * 60000 -> StaffMinute(terminal, 45 * 60000, 0, 0, 0)
    )

    summary === expected
  }

  "PortState " should {
    "correctly calculate UTC time days when creating a summary spanning a clock change" in {
      val summaries = PortState.empty.dailyCrunchSummary(SDate("2022-03-27", Crunch.utcTimeZone), 7, T1, List(EeaDesk))
      val days = summaries.keys.toList.sorted.map(SDate(_))

      val expected = List(
        SDate(2022, 3, 27, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 28, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 29, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 30, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 31, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 4, 1, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 4, 2, 0, 0, Crunch.utcTimeZone),
      )

      days === expected
    }

    "correctly calculate local time days when creating a summary spanning a clock change" in {
      val summaries = PortState.empty.dailyCrunchSummary(SDate("2022-03-27", Crunch.europeLondonTimeZone), 7, T1, List(EeaDesk))
      val days = summaries.keys.toList.sorted.map(SDate(_))

      val expected = List(
        SDate(2022, 3, 27, 0, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 27, 23, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 28, 23, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 29, 23, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 30, 23, 0, Crunch.utcTimeZone),
        SDate(2022, 3, 31, 23, 0, Crunch.utcTimeZone),
        SDate(2022, 4, 1, 23, 0, Crunch.utcTimeZone),
      )

      days === expected
    }
  }
}
