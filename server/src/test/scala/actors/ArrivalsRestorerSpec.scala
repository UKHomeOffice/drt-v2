package actors

import drt.shared.PortCode
import drt.shared.Terminals.{T1, T2}
import drt.shared.api.Arrival
import drt.shared.ArrivalsRestorer
import org.specs2.mutable.Specification

import scala.collection.mutable

class ArrivalsRestorerSpec extends Specification {
  def newRestorer = new ArrivalsRestorer

  val arrival1: Arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, schDt = "2021-05-01T10:20")
  val arrival2: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T2, schDt = "2021-05-02T07:40", origin = PortCode("JFK"))
  val arrival3: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T2, schDt = "2021-05-02T07:40", origin = PortCode("TFF"))

  "Given one update and no removals " +
    "The state should first contain the arrival, and then after calling finish() should contain no arrivals" >> {
    val restorer = newRestorer

    restorer.update(Seq(arrival1))

    restorer.arrivals === mutable.SortedMap(arrival1.unique -> arrival1)

    restorer.finish()

    restorer.arrivals.isEmpty === true
  }

  "Given updates containing arrival1 & arrival2 & arrival3 and one legacy hash removal for arrival2" +
    "The state should contain just arrival1, as the hash will match both arrival2 & 3 (they are the same apart from their origin)" >> {
    val items = Seq(arrival1, arrival2, arrival3)
    val restorer = newRestorer

    restorer.update(items)
    restorer.removeHashLegacies(Seq(arrival2.unique.legacyUniqueId))

    restorer.arrivals === mutable.Map(arrival1.unique -> arrival1)
  }

  "Given updates containing arrival1 & arrival2 & arrival3 and one legacy non-hash removal for arrival2" +
    "The state should contain just arrival1, as the legacy unique arrival will match both arrival2 & 3 (they are the same apart from their origin)" >> {
    val items = Seq(arrival1, arrival2, arrival3)
    val restorer = newRestorer

    restorer.update(items)
    restorer.remove(Seq(arrival2.unique.legacyUniqueArrival))

    restorer.arrivals === mutable.Map(arrival1.unique -> arrival1)
  }

  "Given updates containing arrival1 & arrival2 & arrival3 and one non-legacy removal for arrival2" +
    "The state should contain arrival1 & arrival3 - the new unique arrival containing the origin only matches arrival2" >> {
    val items = Seq(arrival1, arrival2, arrival3)
    val restorer = newRestorer

    restorer.update(items)
    restorer.remove(Seq(arrival2.unique))

    restorer.arrivals === mutable.Map(arrival1.unique -> arrival1, arrival3.unique -> arrival3)
  }
}

