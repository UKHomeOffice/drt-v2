package drt.shared.api

import ujson.Value
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default
import upickle.default.{read, writeJs}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

case class ForecastAccuracy(localDate: LocalDate, pax: Map[Terminal, SortedMap[Int, Option[Double]]])

object ForecastAccuracy {
  implicit val rw: default.ReadWriter[ForecastAccuracy] = upickle.default.readwriter[Value].bimap[ForecastAccuracy](
    fa => {
      val terminalDaysAccuracy: Map[Terminal, Map[String, Option[Double]]] = fa.pax
        .map { case (t, vs) =>
          val dateStringsWithAccuracies = vs.map {
            case (d, acc) => (d.toString, acc)
          }
          (t, dateStringsWithAccuracies)
        }
      ujson.Obj.from(Seq(
        "localDate" -> writeJs(fa.localDate),
        "pax" -> writeJs(terminalDaysAccuracy)))
    },
    v => ForecastAccuracy(
      read[LocalDate](v("localDate")),
      read[Map[Terminal, Map[String, Option[Double]]]](v("pax")).mapValues {
        accuracies =>
          SortedMap[Int, Option[Double]]() ++ accuracies.map {
            case (dStr, acc) => Integer.parseInt(dStr) -> acc
          }
      }.toMap
    )
  )
}
