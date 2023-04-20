package actors

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsRestorer}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}

import scala.collection.mutable

class ArrivalsRestorerSpec extends Specification {
  def newRestorer = new ArrivalsRestorer[Arrival]

  val arrivalT1: Arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, schDt = "2021-05-01T10:20")
  val arrivalT2JFK: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T2, schDt = "2021-05-02T07:40", origin = PortCode("JFK"))
  val arrivalT2TFF: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T2, schDt = "2021-05-02T07:40", origin = PortCode("TFF"))

  "Given an arrivals restorer " >> {
    "When I give it one updated arrival and no arrivals to remove" >> {
      "The state should first contain the arrival, and then after calling finish() should contain no arrivals" >> {
        val restorer = newRestorer

        restorer.applyUpdates(Seq(arrivalT1))

        restorer.arrivals === mutable.SortedMap(arrivalT1.unique -> arrivalT1)

        restorer.finish()

        restorer.arrivals.isEmpty === true
      }
    }

    "When I give it updates containing arrival1 & arrival2 & arrival3 and one legacy hash removal for arrival2" +
      "The state should contain just arrival1, as the hash will match both arrival2 & 3 (they are the same apart from their origin)" >> {
      val items = Seq(arrivalT1, arrivalT2JFK, arrivalT2JFK)
      val restorer = newRestorer

      restorer.applyUpdates(items)
      restorer.removeHashLegacies(Seq(arrivalT2JFK.unique.legacyUniqueId))

      restorer.arrivals === mutable.Map(arrivalT1.unique -> arrivalT1)
    }

    "Given updates containing arrival-t1, arrival-t2-jfk & arrival-t2-tff and one legacy non-hash removal for arrival-t2-jfk" +
      "The state should contain just arrival-t1, as the legacy unique arrival will match both T2 arrivals (they are the same apart from their origin)" >> {
      val items = Seq(arrivalT1, arrivalT2JFK, arrivalT2JFK)
      val restorer = newRestorer

      restorer.applyUpdates(items)
      restorer.remove(Seq(arrivalT2JFK.unique.legacyUniqueArrival))

      restorer.arrivals === mutable.Map(arrivalT1.unique -> arrivalT1)
    }

    "Given updates containing arrival-t1, arrival-t2-jfk & arrival-t2-tff and one non-legacy removal for arrival-t2-jfk" +
      "The state should contain arrival-t1 & arrival-t2-tff - the new unique arrival containing the origin only matches arrival-t2-jfk" >> {
      val items = Seq(arrivalT1, arrivalT2JFK, arrivalT2TFF)
      val restorer = newRestorer

      restorer.applyUpdates(items)
      restorer.remove(Seq(arrivalT2JFK.unique))

      restorer.arrivals === mutable.Map(arrivalT1.unique -> arrivalT1, arrivalT2TFF.unique -> arrivalT2TFF)
    }
  }
}

