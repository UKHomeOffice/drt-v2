package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.PortState
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{PortCode, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration._

class CodeshareWorkloadSpec extends CrunchTestLike {
  "Given 2 arrivals that are codeshares " +
    "When I monitor pax loads " +
    "I should see only pax loads from the highest pax arrival" >> {
    val sch = "2019-01-01T00:00"
    val arrival1 = ArrivalGenerator.live(iata="BA0001", schDt = sch, totalPax = Option(15), origin = PortCode("AAA"))
    val arrival2 = ArrivalGenerator.live(iata="AA0002", schDt = sch, totalPax = Option(10), origin = PortCode("AAA"))

    val schSdate = SDate(sch)
    val crunch = runCrunchGraph(TestConfig(
      now = () => schSdate,
      setPcpTimes = TestDefaults.setPcpFromBest
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival1, arrival2)))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, crunchMinutes, _) =>
        crunchMinutes.get(TQM(T1, Queues.EeaDesk, schSdate.millisSinceEpoch)) match {
          case Some(minute) =>
            minute.paxLoad == 15
          case _ => false
        }
    }

    val updatedArrival2 = arrival2.copy(estimated = Option(schSdate.addMinutes(1).millisSinceEpoch), totalPax = Option(16))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(updatedArrival2)))

    crunch.portStateTestProbe.fishForMessage(5.seconds) {
      case PortState(_, crunchMinutes, _) =>
        val minute1paxCorrect = crunchMinutes.get(TQM(T1, Queues.EeaDesk, schSdate.millisSinceEpoch)) match {
          case Some(minute) =>
            minute.paxLoad == 0
          case _ => false
        }
        val minute2paxCorrect = crunchMinutes.get(TQM(T1, Queues.EeaDesk, schSdate.addMinutes(1).millisSinceEpoch)) match {
          case Some(minute) =>
            minute.paxLoad == 16
          case _ => false
        }

        minute1paxCorrect && minute2paxCorrect
    }

    success
  }
}
