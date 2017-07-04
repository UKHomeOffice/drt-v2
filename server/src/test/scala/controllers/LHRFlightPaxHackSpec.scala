package controllers

import akka.event.LoggingAdapter
import drt.shared.{Arrival, BestPax}
import org.mockito.Mockito.mock
import org.specs2.mutable.Specification
import services.SDate
import ArrivalGenerator.apiFlight

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
    override def bestPax(f: Arrival): Int = BestPax.bestPax(f)

    override def log: LoggingAdapter = mock(classOf[LoggingAdapter])
  }

  "Find best pax no for flight" >> {
    "Given a flight with non 200 ActPax" >> {
      "Then we should get the ActPax no" >> {
        val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 300, maxPax = 500, lastKnownPax = None, terminal = "T1")

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with 200 ActPax and no LastKnownPax" >> {
      "Then we should get 200" >> {
        val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 200, maxPax = 500, lastKnownPax = None, terminal = "T1")

        200 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with no ActPax and no LastKnownPax with MaxPax of 300" >> {
      "Then we should get 300" >> {
        val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 0, maxPax = 300, lastKnownPax = None, terminal = "T1")

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with default ActPax and LastKnownPax of 300" >> {
      "Then we should get 300" >> {
        val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 0, maxPax = 0, lastKnownPax = Option(300), terminal = "T1")

        300 === BestPax.lhrBestPax(newFlight)
      }
    }
    "Given a flight with no ActPax no MaxPax and no LastKnownPax" >> {
      "Then we should get 200" >> {
        val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 0, maxPax = 0, lastKnownPax = None, terminal = "T1")

        200 === BestPax.lhrBestPax(newFlight)
      }

      "DRT-4632 PcpPax is ActPax - TranPax" >> {
        "Given a flight with ActPax=100 and TranPax=60 THEN we should get pcpPax=40" >> {
          val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324",
            schDt = "2017-06-08T12:00:00.00Z", actPax = 100, tranPax = 60, lastKnownPax = None, terminal = "T1")

          40 === BestPax.lhrBestPax(newFlight)
        }
        "Given a flight with default ActPax, maxPax and tranPax, and with LastKnownPax=400" >> {
          "Then we should get 400" >> {
            val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z",
               lastKnownPax = Option(400), terminal = "T1")
            400 === BestPax.lhrBestPax(newFlight)
          }
        }
        "Given a flight with default ActPax, maxPax and tranPax, and with LastKnownPax=400" >> {
          "Then we should get 400" >> {
            val newFlight = apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z",
               lastKnownPax = Option(400), terminal = "T1")
            400 === BestPax.lhrBestPax(newFlight)
          }
        }
      }
    }

    "Given a list of different flights " >> {
      "when we ask for latest pax numbers for a flight in the list" >> {
        "Then we should get the latest pax numbers for the flight" >> {
          val newFlights = List(
            apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 300, maxPax = 500, lastKnownPax = None, terminal = "T1"),
            apiFlight(flightId = 0, iata = "BA123", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 400, maxPax = 500, lastKnownPax = None, terminal = "T1")
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(newFlights)
          val result = fpx.lastKnownPaxForFlight(apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 0, maxPax = 0, lastKnownPax = None, terminal = "T1"))

          result === Some(300)
        }
      }
      "when we ask for latest pax numbers for a flight not in the list" >> {
        "Then we should get none" >> {
          val newFlights = List(
            apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 300, maxPax = 500, lastKnownPax = None, terminal = "T1"),
            apiFlight(flightId = 0, iata = "BA123", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 400, maxPax = 500, lastKnownPax = None, terminal = "T1")
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(newFlights)
          val result = fpx.lastKnownPaxForFlight(apiFlight(flightId = 0, iata = "SA123", icao = "SA123", schDt = "2017-06-08T12:00:00.00Z", actPax = 0, maxPax = 0, lastKnownPax = None, terminal = "T1"))

          result === None
        }
      }
    }
    "Given a list of flights containing default pax numbers" >> {
      "When we have historic pax nos for that flight" >> {
        "Then we should see the historic paxnos in the LastKnownPax field for those flights" >> {

          val oldFlights = List(
            apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-07T12:00:00.00Z", actPax = 300, maxPax = 0, lastKnownPax = None, terminal = "T1"),
            apiFlight(flightId = 0, iata = "BA124", icao = "BA124", schDt = "2017-06-07T12:00:00.00Z", actPax = 300, maxPax = 0, lastKnownPax = None, terminal = "T1")
          )

          val newFlights = List(
            apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 200, maxPax = 0, lastKnownPax = None, terminal = "T1"),
            apiFlight(flightId = 0, iata = "BA124", icao = "BA124", schDt = "2017-06-08T12:00:00.00Z", actPax = 200, maxPax = 0, lastKnownPax = None, terminal = "T1")
          )
          val expected = List(
            apiFlight(flightId = 0, iata = "SA324", icao = "SA324", schDt = "2017-06-08T12:00:00.00Z", actPax = 200, maxPax = 0, lastKnownPax = Option(300), terminal = "T1"),
            apiFlight(flightId = 0, iata = "BA124", icao = "BA124", schDt = "2017-06-08T12:00:00.00Z", actPax = 200, maxPax = 0, lastKnownPax = Option(300), terminal = "T1")
          )

          val fpx = flightPaxNumbers
          fpx.storeLastKnownPaxForFlights(oldFlights)
          val result = fpx.addLastKnownPaxNos(newFlights)

          result === expected
        }
      }
    }
  }
}

object ArrivalGenerator {

  def apiFlight(
                 flightId: Int,
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Int = 0,
                 maxPax: Int = 0,
                 lastKnownPax: Option[Int] = None,
                 terminal: String = "T1",
                 origin: String = "",
                 operator: String = "",
                 status: String = "",
                 estDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: String = "",
                 stand: String = "",
                 tranPax: Int = 0,
                 runwayId: String = "",
                 baggageReclaimId: String = "",
                 airportId: String = ""
               ): Arrival =
    Arrival(
      FlightID = flightId,
      rawICAO = icao,
      rawIATA = iata,
      SchDT = schDt,
      ActPax = actPax,

      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = status,
      EstDT = estDt,
      ActDT = actDt,
      EstChoxDT = estChoxDt,
      ActChoxDT = actChoxDt,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = if (schDt != "") SDate(schDt).millisSinceEpoch else 0,
      LastKnownPax = lastKnownPax
    )
}