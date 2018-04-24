package services.crunch

import drt.shared.{Queues, SDateLike}
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.{LoadMinute, Loads}

class BatchLoadsByCrunchPeriodGraphStageSpec extends Specification {
  "Given existing loads in a list by crunch period " +
    "When I update with new loads for the same period " +
    "Then I should see the existing loads merged with the new loads" >> {
    val crunchPeriodStart = SDate("2018-01-01T00:00")
    val existingLoadsByPeriod = List((crunchPeriodStart.millisSinceEpoch, Loads(Set(LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.millisSinceEpoch)))))
    val newLoad = Loads(Set(LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.addMinutes(1).millisSinceEpoch)))

    val mergedLoadsByPeriod = Crunch.mergeLoadsIntoQueue(newLoad, existingLoadsByPeriod, (_: SDateLike) => crunchPeriodStart)

    val expected = List((crunchPeriodStart.millisSinceEpoch, Loads(Set(
      LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 1, 1, crunchPeriodStart.addMinutes(1).millisSinceEpoch)
    ))))

    mergedLoadsByPeriod === expected
  }
}