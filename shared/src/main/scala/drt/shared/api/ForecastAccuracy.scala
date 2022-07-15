package drt.shared.api

import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default

case class ForecastAccuracy(localDate: LocalDate, pax: Map[Int, Double])

object ForecastAccuracy {
  implicit val rw: default.ReadWriter[ForecastAccuracy] = upickle.default.macroRW[ForecastAccuracy]
}
