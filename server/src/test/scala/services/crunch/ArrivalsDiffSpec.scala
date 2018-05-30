package services.crunch

import controllers.ArrivalGenerator
import drt.shared.ArrivalsDiff
import org.specs2.mutable.Specification
import services.graphstages.Crunch

class ArrivalsDiffSpec extends Specification {
  "Given two ArrivalDiffs " +
  "When I ask for them merged " +
  "Then I should see only of each arrival in the final ArrivalsDiff" >> {
    val arrival1   = ArrivalGenerator.apiFlight(iata = "BA0001", actPax = Option(50), estDt = "2018-01-01T00:00:00")
    val arrival2v1 = ArrivalGenerator.apiFlight(iata = "BA0002", actPax = Option(65), estDt = "2018-01-01T00:10:00")
    val arrival3v1 = ArrivalGenerator.apiFlight(iata = "BA0003", actPax = Option(70), estDt = "2018-01-01T00:20:00")

    val arrival2v2 = ArrivalGenerator.apiFlight(iata = "BA0002", actPax = Option(99), estDt = "2018-01-01T00:11:00")
    val arrival3v2 = ArrivalGenerator.apiFlight(iata = "BA0003", actPax = Option(5), estDt = "2018-01-01T00:19:00")
    val arrival4   = ArrivalGenerator.apiFlight(iata = "BA0003", actPax = Option(5), estDt = "2018-01-01T00:19:00")

    val diff1 = ArrivalsDiff(Set(arrival1, arrival2v1, arrival3v1), Set(1, 2))
    val diff2 = ArrivalsDiff(Set(arrival2v2, arrival3v2, arrival4), Set(2, 3, 4))

    val mergedDiff: ArrivalsDiff = Crunch.mergeArrivalsDiffs(diff1, diff2)

    val expected = ArrivalsDiff(Set(arrival1, arrival2v2, arrival3v2, arrival4), Set(1, 2, 3, 4))

    mergedDiff === expected
  }
}