package services.crunch

import drt.shared.{MilliDate, Queues, SDateLike, TQM}
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

class BatchLoadsByCrunchPeriodGraphStageSpec extends Specification {
  "Given existing loads in a list by crunch period " +
    "When I update with new loads for the same period " +
    "Then I should see the existing loads merged with the new loads" >> {
    val crunchPeriodStart = SDate("2018-01-01T00:00")
    val existingLoadsByPeriod = mutable.SortedMap(
      MilliDate(crunchPeriodStart.millisSinceEpoch) -> Loads(SortedMap(TQM("T1", Queues.EeaDesk, crunchPeriodStart.millisSinceEpoch) -> LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.millisSinceEpoch))))
    val newLoad = Loads(SortedMap(TQM("T1", Queues.EeaDesk, crunchPeriodStart.addMinutes(1).millisSinceEpoch) -> LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.addMinutes(1).millisSinceEpoch)))

    Crunch.mergeLoadsIntoQueue(newLoad, existingLoadsByPeriod, (_: SDateLike) => crunchPeriodStart)

    val mergedLoadsByPeriod = existingLoadsByPeriod

    val expected = Map(MilliDate(crunchPeriodStart.millisSinceEpoch) -> Loads(SortedMap(
      TQM("T1", Queues.EeaDesk, crunchPeriodStart.millisSinceEpoch) -> LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.millisSinceEpoch),
      TQM("T1", Queues.EeaDesk, crunchPeriodStart.addMinutes(1).millisSinceEpoch) -> LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.addMinutes(1).millisSinceEpoch)
    )))

    mergedLoadsByPeriod === expected
  }
}
