package drt.shared.api

import ujson.Value
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default
import upickle.default.{read, writeJs}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

case class ForecastAccuracy(localDate: LocalDate, pax: Map[Terminal, SortedMap[Int, Double]])

object ForecastAccuracy {
  implicit val rw: default.ReadWriter[ForecastAccuracy] = upickle.default.readwriter[Value].bimap[ForecastAccuracy](
    fa => {
      val terminalDaysAccuracy: Map[Terminal, Map[String, Double]] = fa.pax.map { case (t, vs) => (t, vs.toMap.map {
        case (d, acc) => (d.toString, acc)
      }) }
      ujson.Obj(mutable.LinkedHashMap(
        "localDate" -> writeJs(fa.localDate),//ujson.Str(fa.localDate.toString),
        "pax" -> writeJs(terminalDaysAccuracy)))
    },
    v => ForecastAccuracy(
      read[LocalDate](v("localDate")),
      read[Map[Terminal, Map[String, Double]]](v("pax")).mapValues {
        accs => SortedMap[Int, Double]() ++ accs.map {
          case (dStr, acc) => (Integer.parseInt(dStr) -> acc)
        }
      }
    )
  )
}
