package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.T1
import drt.shared.{PortCode, PortState, Queues, TQM}
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.concurrent.duration._

class CodeshareWorkloadSpec extends CrunchTestLike {
  "Given 2 arrivals that are codeshares " +
    "When I monitor pax loads " +
    "I should see only pax loads from the highest pax arrival" >> {
    val sch = "2019-01-01T00:00"
    val arrival1 = ArrivalGenerator.arrival(iata="BA0001", schDt = sch, actPax = Option(15), origin = PortCode("AAA"))
    val arrival2 = ArrivalGenerator.arrival(iata="AA0002", schDt = sch, actPax = Option(10), origin = PortCode("AAA"))

    val schSdate = SDate(sch)
    val crunch = runCrunchGraph(
      now = () => schSdate,
      pcpArrivalTime = pcpForFlightFromBest
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival1, arrival2))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case PortState(_, crunchMinutes, _) =>
        crunchMinutes.get(TQM(T1, Queues.EeaDesk, schSdate.millisSinceEpoch)) match {
          case Some(minute) => minute.paxLoad == 15
          case _ => false
        }
    }

    val updatedArrival2 = arrival2.copy(Estimated = Option(schSdate.addMinutes(1).millisSinceEpoch), ActPax = Option(16))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(updatedArrival2))))

    crunch.portStateTestProbe.fishForMessage(5 seconds) {
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

    crunch.liveArrivalsInput.complete()

    success
  }
}
