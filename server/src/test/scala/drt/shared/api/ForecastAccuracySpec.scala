package drt.shared.api

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable.SortedMap

class ForecastAccuracySpec extends Specification {
  "Given a ForecastAccuracy class" >> {
    "I should be able to serialise and deserialise it with upickle without loss" >> {
      val acc = ForecastAccuracy(LocalDate(2022, 7, 15), Map(
        T1 -> SortedMap(1 -> Option(0.9), 2 -> Option(0.85), 3 -> Option(0.7)),
        T2 -> SortedMap(1 -> Option(0.91), 2 -> Option(0.86), 3 -> Option(0.71)),
      ))
      val json = upickle.default.write(acc)
      val acc2 = upickle.default.read[ForecastAccuracy](json)
      acc2 mustEqual acc
    }
  }
}
