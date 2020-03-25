package services.graphstages

import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneDayMillis
import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.specification.AfterEach
import services.arrivals.ArrivalDataSanitiser
import services.crunch.CrunchTestLike
import services.{PcpArrival, SDate}

import scala.collection.mutable
import scala.concurrent.duration._
import actors.ArrivalGenerator.arrival

class ArrivalsGraphStagePaxNosSpec extends CrunchTestLike with AfterEach {
  sequential
  isolated

  override def after: Unit = TestKit.shutdownActorSystem(system)

  val nowForThisTest: SDateLike = SDate(2019, 10, 1, 16, 0)

  private def buildArrivalsGraphStage = new ArrivalsGraphStage(
    "",
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    pcpTimeCalc,
    Set(T1),
    ArrivalDataSanitiser(None, None),
    oneDayMillis,
    () => nowForThisTest
  )

  def pcpTimeCalc(a: Arrival): MilliDate = PcpArrival.pcpFrom(0, 0, _ => 0)(a)

  "Given a live arrival with 0 pax that is not landing for more than 6 hours " +
    "Then we should use the ACL pax numbers" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, _, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(
      List(arrival(actPax = Option(189), schDt = nowForThisTest.addHours(6).toISOString()))
    )
    liveSource.offer(
      List(arrival(actPax = Option(0),
        schDt = nowForThisTest.addHours(6).toISOString(),
        gate = Option("G1")))
    )

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(189) && a.Gate == Option("G1")
        }
    }
    success
  }

  "Given a live arrival with 0 pax that is not landing for more than 6 hours with a forecast record " +
    "Then we should use the Forecast pax numbers" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, forecastSource, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(
      List(arrival(actPax = Option(189), schDt = nowForThisTest.addHours(6).toISOString()))
    )
    forecastSource.offer(
      List(arrival(actPax = Option(100), schDt = nowForThisTest.addHours(6).toISOString()))
    )
    liveSource.offer(
      List(arrival(
        actPax = Option(0),
        schDt = nowForThisTest.addHours(6).toISOString(),
        gate = Option("G1")))
    )

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(100) && a.Gate == Option("G1")
        }
    }
    success
  }

  "Given a live arrival with 0 pax in the live feed that has landed " +
    "Then we should assume that 0 is the correct number of pax regardless of forecast/acl numbers" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, forecastSource, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(List(arrival(actPax = Option(189), schDt = nowForThisTest.toISOString())))
    forecastSource.offer(List(arrival(actPax = Option(100), schDt = nowForThisTest.toISOString())))
    liveSource.offer(List(arrival(actPax = Option(0), schDt = nowForThisTest.toISOString(), gate = Option("G1"))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(0) && a.Gate == Option("G1")
        }
    }
    success
  }

  "Given a live arrival with 0 pax in the live feed that is scheduled for now " +
    "Then we should assume that 0 is the correct number of pax" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, _, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(List(arrival(actPax = Option(189), schDt = nowForThisTest.toISOString())))
    liveSource.offer(List(arrival(actPax = Option(0), schDt = nowForThisTest.toISOString(), gate = Option("G1"))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(0) && a.Gate == Option("G1")
        }
    }
    success
  }

  "Given a live arrival with 0 pax in the live feed that has landed regardless of schedule time " +
    "Then we should assume that 0 is the correct number of pax" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, _, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(
      List(arrival(actPax = Option(189), schDt = nowForThisTest.addHours(8).toISOString()))
    )
    liveSource.offer(
      List(arrival(
        actPax = Option(0),
        schDt = nowForThisTest.addHours(8).toISOString(),
        actChoxDt = nowForThisTest.toISOString(),
        gate = Option("G1")
      ))
    )

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(0) && a.Gate == Option("G1")
        }
    }
    success
  }

  "Given a live arrival with 0 pax in the live feed that has an ActualChox regardless of schedule time " +
    "Then we should assume that 0 is the correct number of pax" >> {
    val probe = TestProbe("arrivals")
    val (aclSource, _, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    aclSource.offer(
      List(arrival(actPax = Option(189), schDt = nowForThisTest.addHours(8).toISOString()))
    )
    liveSource.offer(
      List(arrival(
        actPax = Option(0),
        schDt = nowForThisTest.addHours(8).toISOString(),
        actChoxDt = nowForThisTest.toISOString(),
        gate = Option("G1")
      ))
    )

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ActPax == Option(0) && a.Gate == Option("G1")
        }
    }
    success
  }

}
