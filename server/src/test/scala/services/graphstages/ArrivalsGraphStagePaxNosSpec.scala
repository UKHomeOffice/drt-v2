package services.graphstages

import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.specs2.execute.Success
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._

class ArrivalsGraphStagePaxNosSpec extends CrunchTestLike {
  "Given an empty port state" >> {
    val nowString = "2020-04-01T00:00"
    val crunch = runCrunchGraph(now = () => SDate(nowString))

    def fishForArrivalWithActPax(actPax: Option[Int], status: String = ""): Success = {
      crunch.portStateTestProbe.fishForMessage(1 second) {
        case PortState(flights, _, _) =>
          flights.values.toList.exists(fws => fws.apiFlight.flightCode == "BA0001" && fws.apiFlight.ActPax == actPax && fws.apiFlight.Status == ArrivalStatus(status))
      }

      success
    }

    "When I send an ACL arrival with zero pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(0), tranPax = None, maxPax = Option(150))
      "I should see it with 0 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(0))
      }
    }

    "When I send an ACL arrival with 100 pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      "I should see it with 100 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(100))
      }
    }

    "When I send an ACL arrival with 100 pax and a forecast arrival with undefined pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.forecastArrivalsInput, scheduled = nowString, actPax = None, status = "updated", tranPax = None, maxPax = None)
      "I should see it with 100 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(100), "updated")
      }
    }

    "When I send an ACL arrival with 100 pax and a forecast arrival with 0 pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.forecastArrivalsInput, scheduled = nowString, actPax = Option(0), tranPax = None, maxPax = None, "updated")
      "I should see it with 0 ActPax in the port state - ignoring the forecast feed's zero" >> {
        fishForArrivalWithActPax(Option(0), "updated")
      }
    }

    "When I send an ACL arrival with 100 pax and a forecast arrival with 50 pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.forecastArrivalsInput, scheduled = nowString, actPax = Option(50), tranPax = None, maxPax = None)
      "I should see it with 50 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(50))
      }
    }

    "When I send a live arrival with 100 pax, undefined trans pax and no other source" >> {
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      "I should see it with 100 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(100))
      }
    }

    "When I send an ACL arrival with 100 pax and a live arrival with undefined pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = nowString, actPax = None, tranPax = None, maxPax = Option(150), "updated")
      "I should see it with 100 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(100), "updated")
      }
    }

    "When I send an ACL arrival with 100 pax and a live arrival with 0 pax, scheduled more than 3 hours after now" >> {
      val scheduled3Hrs5MinsAfterNow = SDate(nowString).addHours(3).addMinutes(5).toISOString()
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(0), tranPax = None, maxPax = Option(150), "updated")
      "I should see it with 0 ActPax in the port state (trust zero even when not close to landing)" >> {
        fishForArrivalWithActPax(Option(0), "updated")
      }
    }

    "When I send an ACL arrival with 100 pax and a live arrival with 0 pax, scheduled more than 3 hours after now, but with an actChox time, ie it's landed" >> {
      val scheduled3Hrs5MinsAfterNow = SDate(nowString).addHours(3).addMinutes(5).toISOString()
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(0), tranPax = None, maxPax = Option(150), actChoxDt = scheduled3Hrs5MinsAfterNow)
      "I should see it with 0 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(0))
      }
    }

    "When I send an ACL arrival with 100 pax and a live arrival with 50 pax, scheduled more than 3 hours after now" >> {
      val scheduled3Hrs5MinsAfterNow = SDate(nowString).addHours(3).addMinutes(5).toISOString()
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = scheduled3Hrs5MinsAfterNow, actPax = Option(50), tranPax = None, maxPax = Option(150))
      "I should see it with 50 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(50))
      }
    }

    "When I send an ACL arrival with 100 pax and a live arrival with 0 pax, scheduled less than 3 hours after now" >> {
      val scheduled2Hrs55MinsAfterNow = SDate(nowString).addHours(2).addMinutes(55).toISOString()
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = scheduled2Hrs55MinsAfterNow, actPax = Option(100), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = scheduled2Hrs55MinsAfterNow, actPax = Option(0), tranPax = None, maxPax = Option(150))
      "I should see it with 0 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(0))
      }
    }

    "When I send a live arrival with undefined pax" >> {
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = nowString, actPax = None, tranPax = None, maxPax = None)
      "I should see it with undefined pax in the port state" >> {
        fishForArrivalWithActPax(None)
      }
    }

    "When I send an ACL arrival with 0 pax and a live arrival with undefined pax, with max pax of 150" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(0), tranPax = None, maxPax = Option(150))
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = nowString, actPax = None, tranPax = None, maxPax = Option(150), "updated")
      "I should see it with 0 ActPax in the port state" >> {
        fishForArrivalWithActPax(Option(0), "updated")
      }
    }

    "Given an arrival with a zero pax, undefined trans pax, and max pax of 100" >> {
      val arrival = ArrivalGenerator.arrival(actPax = Option(0), tranPax = None, maxPax = Option(100))
      "When I ask for the best pax" >> {
        val bestPax = PcpPax.bestPax(arrival)
        "I should see 0" >> {
          bestPax === 0
        }
      }
    }

    "When I send an ACL arrival with 100 pax & undefined trans pax, a forecast arrival with 50 pax & 10 trans pax, and a live arrival with undefined pax" >> {
      offerArrivalAndWait(crunch.baseArrivalsInput, scheduled = nowString, actPax = Option(100), tranPax = None, maxPax = None)
      offerArrivalAndWait(crunch.forecastArrivalsInput, scheduled = nowString, actPax = Option(50), tranPax = Option(10), maxPax = None)
      offerArrivalAndWait(crunch.liveArrivalsInput, scheduled = nowString, actPax = None, tranPax = None, maxPax = None)
      "I should see it with 50 ActPax in the port state (from forecast)" >> {
        fishForArrivalWithActPax(Option(50))
      }
    }
  }

  def offerArrivalAndWait(input: SourceQueueWithComplete[ArrivalsFeedResponse],
                          scheduled: String,
                          actPax: Option[Int],
                          tranPax: Option[Int],
                          maxPax: Option[Int],
                          status: String = "",
                          actChoxDt: String = ""): QueueOfferResult = {
    val arrivalLive = ArrivalGenerator.arrival("BA0001", schDt = scheduled, actPax = actPax, tranPax = tranPax, maxPax = maxPax, status = ArrivalStatus(status), actChoxDt = actChoxDt)
    offerAndWait(input, ArrivalsFeedSuccess(Flights(Seq(arrivalLive))))
  }
}
