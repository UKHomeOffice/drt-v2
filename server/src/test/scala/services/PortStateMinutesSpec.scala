package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.specs2.mutable.Specification
import services.graphstages.{DeskRecMinute, DeskRecMinutes, SimulationMinute, SimulationMinutes}

import scala.collection.immutable.Map

class PortStateMinutesSpec extends Specification {
  val now: MillisSinceEpoch = SDate.now().millisSinceEpoch

  "When I apply a FlightsWithSplits " >> {
    "Containing only new arrivals " >> {
      val newFlightsWithSplits = FlightsWithSplits(
        (1 to 5).map(d => ApiFlightWithSplits(
          ArrivalGenerator.arrival(iata = "BA0001", schDt = s"2019-01-0${d}T12:00.00Z", terminal = "T1"), Set())
        ), Seq())

      "To an empty PortState" >> {
        "Then I should see those flights in the PortState" >> {
          val portState = PortStateMutable.empty
          newFlightsWithSplits.applyTo(portState, now)
          val expected = PortState(newFlightsWithSplits.flightsToUpdate.toList.map(_.copy(lastUpdated = Option(now))), List(), List()).mutable

          portState.flights.all === expected.flights.all
        }
      }

      "To an existing PortState which already has some flights" >> {
        "Then I should see the existing and new flights in the PortState" >> {
          val existingFlights = (1 to 5).map(d =>
            ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA2222", schDt = s"2019-01-0${d}T12:00.00Z", terminal = "T1"), Set()))

          val existingPortState = PortState(existingFlights.toList, List(), List()).mutable
          newFlightsWithSplits.applyTo(existingPortState, now)
          val portState = existingPortState.immutable
          val expected = existingFlights.toSet ++ newFlightsWithSplits.flightsToUpdate.map(_.copy(lastUpdated = Option(now))).toSet

          portState.flights.values.toSet === expected
        }
      }
    }
  }

  "When I apply a StaffMinutes " >> {
    "Containing only new minutes " >> {
      val newStaffMinutes = StaffMinutes((1 to 5).map(d => StaffMinute("T1", d, d, d, d, None)))

      "To an empty PortState" >> {
        "Then I should see those minutes in the PortState" >> {
          val portState = PortStateMutable.empty
          newStaffMinutes.applyTo(portState, now)
          val expected = PortState(List(), List(), newStaffMinutes.minutes.toList.map(_.copy(lastUpdated = Option(now))))

          portState.staffMinutes.all === expected.staffMinutes
        }
      }

      "To an existing PortState which already has some minutes" >> {
        "Then I should see the existing and new minutes in the PortState" >> {
          val existingStaffMinutes = StaffMinutes((100 to 105).map(d => StaffMinute("T1", d, d, d, d, None)))

          val existingPortState = PortState(List(), List(), existingStaffMinutes.minutes.toList).mutable
          newStaffMinutes.applyTo(existingPortState, now)
          val portState = existingPortState.immutable
          val expectedMinutes = existingStaffMinutes.minutes ++ newStaffMinutes.minutes.map(_.copy(lastUpdated = Option(now)))
          val expected = PortState(List(), List(), expectedMinutes.toList)

          portState.staffMinutes === expected.staffMinutes
        }
      }
    }
  }

  "When I apply a ActualDeskStats " >> {
    "Containing only new minutes " >> {
      val newActualDeskStats = ActualDeskStats(Map("T1" -> Map(Queues.EeaDesk -> (0 until 5 * 60000 * 15 by 60000 * 15).map(d => (d.toLong, DeskStat(Option(d), Option(d)))).toMap)))
      val newCrunchMinutes = newActualDeskStats.minutes.map {
        case (TQM(t, q, m), ds) => CrunchMinute(t, q, m, 0, 0, 0, 0, None, None, ds.desks, ds.waitTime, Option(now))
      }

      "To an empty PortState" >> {
        "Then I should see those desk stats in the PortState crunch minutes" >> {
          val portState = PortStateMutable.empty
          newActualDeskStats.applyTo(portState, now)
          val expected = PortState(List(), newCrunchMinutes.toList, List())

          portState.crunchMinutes.all === expected.crunchMinutes
        }
      }

      "To an existing PortState which already has some minutes" >> {
        "Then I should see the existing and new desk stats in the PortState crunch minutes" >> {
          val existingActualDeskStats = ActualDeskStats(Map("T1" -> Map(Queues.EeaDesk -> (100 until 105 * 60000 * 15 by 60000 * 15).map(d => (d.toLong, DeskStat(Option(d), Option(d)))).toMap)))
          val existingCrunchMinutes = existingActualDeskStats.minutes.map {
            case (TQM(t, q, m), ds) => CrunchMinute(t, q, m, 0, 0, 0, 0, None, None, ds.desks, ds.waitTime, Option(now))
          }
          val existingPortState = PortState(List(), existingCrunchMinutes.toList, List()).mutable
          newActualDeskStats.applyTo(existingPortState, now)
          val portState = existingPortState.immutable
          val expectedMinutes = existingCrunchMinutes ++ newCrunchMinutes.map(_.copy(lastUpdated = Option(now)))
          val expected = PortState(List(), expectedMinutes.toList, List())

          portState.staffMinutes === expected.staffMinutes
        }
      }
    }
  }

  "When I apply a DeskRecMinutes " >> {
    "Containing only new minutes " >> {
      val newDeskRecMinutes = DeskRecMinutes((1 to 5).map(d => DeskRecMinute("T1", Queues.EeaDesk, d, d, d, d, d)))
      val newCrunchMinutes = newDeskRecMinutes.minutes.toList.map(CrunchMinute(_).copy(lastUpdated = Option(now)))

      "To an empty PortState" >> {
        "Then I should see those minutes in the PortState" >> {
          val portState = PortStateMutable.empty
          newDeskRecMinutes.applyTo(portState, now)
          val expected = PortState(List(), newCrunchMinutes, List())

          portState.crunchMinutes.all === expected.crunchMinutes
        }
      }

      "To an existing PortState which already has some minutes" >> {
        "Then I should see the existing and new minutes in the PortState" >> {
          val existingDeskRecMinutes = DeskRecMinutes((100 to 105).map(d => DeskRecMinute("T1", Queues.EeaDesk, d, d, d, d, d)))
          val existingCrunchMinutes = existingDeskRecMinutes.minutes.toList.map(CrunchMinute(_))
          val existingPortState = PortState(List(), existingCrunchMinutes, List()).mutable
          newDeskRecMinutes.applyTo(existingPortState, now)
          val portState = existingPortState.immutable
          val expectedMinutes = existingCrunchMinutes ++ newCrunchMinutes.map(_.copy(lastUpdated = Option(now)))
          val expected = PortState(List(), expectedMinutes, List())

          portState.crunchMinutes === expected.crunchMinutes
        }
      }
    }
  }

  "When I apply a SimulationMinutes " >> {
    "Containing only new minutes " >> {
      val newSimulationMinutes = SimulationMinutes((1 to 5).map(d => SimulationMinute("T1", Queues.EeaDesk, d, d, d)))
      val newCrunchMinutes = newSimulationMinutes.minutes.toList.map(CrunchMinute(_).copy(lastUpdated = Option(now)))

      "To an empty PortState" >> {
        "Then I should see those minutes in the PortState" >> {
          val portState = PortStateMutable.empty
          newSimulationMinutes.applyTo(portState, now)
          val expected = PortState(List(), newCrunchMinutes, List())

          portState.crunchMinutes.all === expected.crunchMinutes
        }
      }

      "To an existing PortState which already has some minutes" >> {
        "Then I should see the existing and new minutes in the PortState" >> {
          val existingSimulationMinutes = SimulationMinutes((100 to 105).map(d => SimulationMinute("T1", Queues.EeaDesk, d, d, d)))
          val existingCrunchMinutes = existingSimulationMinutes.minutes.toList.map(CrunchMinute(_))
          val existingPortState = PortState(List(), existingCrunchMinutes, List()).mutable
          newSimulationMinutes.applyTo(existingPortState, now)
          val portState = existingPortState.immutable
          val expectedMinutes = existingCrunchMinutes ++ newCrunchMinutes.map(_.copy(lastUpdated = Option(now)))
          val expected = PortState(List(), expectedMinutes, List())

          portState.crunchMinutes === expected.crunchMinutes
        }
      }
    }
  }
}
