package controllers

import akka.event.LoggingAdapter
import drt.shared.{AirportConfig, Arrival, BestPax}
import org.mockito.Mockito.mock
import org.specs2.mutable.Specification

class LHRFlightPaxHackSpec extends Specification {
  isolated
  def flightWithBestPax(newFlight: Arrival, currentFlights: List[Arrival]): Arrival = {
    if (newFlight.ActPax != 200)
      newFlight
    else {
      val lastFlight = currentFlights.find(f => f.IATA == newFlight.IATA)

      val flight = lastFlight.map(f => {
        newFlight.copy(LastKnownPax = Option(f.ActPax))
      })

      flight.getOrElse(newFlight)
    }
  }
  def flightPaxNumbers = new FlightState {
    override def airportConfig: AirportConfig = airportConfig

    override def log: LoggingAdapter = mock(classOf[LoggingAdapter])
  }

  "Find best pax no for flight" >> {
    "Given a flight with non 200 ActPax" >> {
      "Then we should get the ActPax no" >> {
        val newFlight = apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 300, 500, None)

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with 200 ActPax and no LastKnownPax" >> {
      "Then we should get 200" >> {
        val newFlight = apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 200, 500, None)

        200 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with no ActPax and no LastKnownPax with MaxPax of 300" >> {
      "Then we should get 300" >> {
        val newFlight = apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 0, 300, None)

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with default ActPax and LastKnownPax of 300" >> {
      "Then we should get 300" >> {
        val newFlight = apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 0, 0, Option(300))

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with no ActPax no MaxPax and no LastKnownPax" >> {
      "Then we should get 200" >> {
        val newFlight = apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 0, 0, None)

        200 === BestPax.lhrBestPax(newFlight)
      }
    }

    "Given a list of different flights " >> {
      "when we ask for latest pax numbers for a flight in the list" >> {
        "Then we should get the latest pax numbers for the flight" >> {
          val newFlights = List(
            apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 300, 500),
            apiFlight("BA123", "SA324", "2017-06-08T12:00:00.00Z", 400, 500)
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(newFlights)
          val result = fpx.lastKnownPaxForFlight(apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 0, 0))

          result === Some(300)
        }
      }
      "when we ask for latest pax numbers for a flight not in the list" >> {
        "Then we should get none" >> {
          val newFlights = List(
          apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 300, 500),
          apiFlight("BA123", "SA324", "2017-06-08T12:00:00.00Z", 400, 500)
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(newFlights)
          val result = fpx.lastKnownPaxForFlight(apiFlight("SA123", "SA123", "2017-06-08T12:00:00.00Z", 0, 0))

          result === None
        }
      }
    }
    "Given a list of flights containing default pax numbers" >> {
      "When we have historic pax nos for that flight" >> {
        "Then we should see the historic paxnos in the LastKnownPax field for those flights" >> {

          val oldFlights = List(
          apiFlight("SA324", "SA324", "2017-06-07T12:00:00.00Z", 300, 0),
          apiFlight("BA124", "BA124", "2017-06-07T12:00:00.00Z", 300, 0)
          )

          val newFlights = List(
          apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 200, 0),
          apiFlight("BA124", "BA124", "2017-06-08T12:00:00.00Z", 200, 0)
          )
          val expected = List(
          apiFlight("SA324", "SA324", "2017-06-08T12:00:00.00Z", 200, 0, Option(300)),
          apiFlight("BA124", "BA124", "2017-06-08T12:00:00.00Z", 200, 0, Option(300))
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(oldFlights)
          val result = fpx.addLastKnownPaxNos(newFlights)

          result === expected
        }
      }
    }
  }


  def apiFlight(iataCode: String, icaoCode: String, schDt: String, actPax: Int, maxPax: Int, lastKnownPax: Option[Int] = None): Arrival =
  Arrival(
  FlightID = 0,
  rawICAO = icaoCode,
  rawIATA = iataCode,
  SchDT = schDt,
  ActPax = actPax,

  Terminal = "T1",
  Origin = "",
  Operator = "",
  Status = "",
  EstDT = "",
  ActDT = "",
  EstChoxDT = "",
  ActChoxDT = "",
  Gate = "",
  Stand = "",
  MaxPax = maxPax,
  TranPax = 0,
  RunwayID = "",
  BaggageReclaimId = "",
  AirportID = "",
  PcpTime = 0,
  LastKnownPax = lastKnownPax
  )

}
