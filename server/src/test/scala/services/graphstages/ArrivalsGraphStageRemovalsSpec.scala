package services.graphstages

import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared._
import services.crunch.CrunchTestLike
import services.{PcpArrival, SDate}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration._

class ArrivalsGraphStageRemovalsSpec extends CrunchTestLike {

  val probe: TestProbe = TestProbe("arrivals")

  val dayOfArrivals: SDateLike = SDate("2021-03-01T12:00Z")

  def pcpTimeCalc(a: Arrival, r: RedListUpdates): MilliDate = PcpArrival.pcpFrom(0, 0, (_, _) => 0)(a, r)

  "Given an ACL feed with 2 arrivals, followed by another with only one of them, the other arrival should be removed" >> {
    val (aclSource, _, _, _, _) = TestableArrivalsGraphStage(
      probe,
      TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, dayOfArrivals)
    ).run

    val arrivalThatIsRemoved = ArrivalGenerator.arrival(schDt = dayOfArrivals.toISOString())
    val arrivalThatRemains = ArrivalGenerator.arrival(schDt = dayOfArrivals.addHours(1).toISOString())

    val firstAclFeed = List(arrivalThatIsRemoved, arrivalThatRemains)
    aclSource.offer(firstAclFeed)

    probe.fishForMessage(1.second) {
      case ArrivalsDiff(_, removals) => removals.isEmpty
    }

    val secondAclFeed = List(arrivalThatRemains)
    aclSource.offer(secondAclFeed)

    probe.fishForMessage(1.second) {
      case ArrivalsDiff(_, removals) => removals.exists(_.Scheduled == arrivalThatIsRemoved.Scheduled)
    }

    success
  }

  "Given an ACL feed with a flight removal on the day after that flight is scheduled, then we should ignore the removal" >> {
    val (aclSource, _, _, _, _) = TestableArrivalsGraphStage(
      probe,
      TestableArrivalsGraphStage.buildArrivalsGraphStage(
        pcpTimeCalc,
        dayOfArrivals.getUtcLastMidnight.addDays(1))
    ).run

    val arrivalThatIsRemoved = ArrivalGenerator.arrival(schDt = dayOfArrivals.toISOString())
    val arrivalThatRemains = ArrivalGenerator.arrival(schDt = dayOfArrivals.addHours(1).toISOString())
    val newArrival = ArrivalGenerator.arrival(schDt = dayOfArrivals.addHours(2).toISOString())

    val firstAclFeed = List(arrivalThatIsRemoved, arrivalThatRemains)
    aclSource.offer(firstAclFeed)

    probe.fishForMessage(1.second) {
      case ArrivalsDiff(updates, removals) =>
        removals.isEmpty
    }

    val secondAclFeed = List(arrivalThatRemains, newArrival)
    aclSource.offer(secondAclFeed)

    probe.fishForMessage(1.second) {
      case ArrivalsDiff(_, removals) => removals.isEmpty
    }

    success
  }
}
