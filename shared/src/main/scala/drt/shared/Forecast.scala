package drt.shared

import drt.shared.CrunchApi.{ForecastPeriod, ForecastTimeSlot, MillisSinceEpoch}

object Forecast {

  val timeslotsOnBSTToUTCChangeDay = 100
  val timeslotsOnRegualarDay = 96
  val timeslotsOnUTCToBSTChangeDay = 92

  def transposeIrregular[A](xs: List[List[A]]): List[List[A]] = xs.filter(_.nonEmpty) match {
    case Nil => Nil
    case ys: List[List[A]] => ys.map(_.head) :: transposeIrregular(ys.map(_.tail))
  }

  def periodByTimeSlotAcrossDays(forecastPeriod: ForecastPeriod): List[List[Option[ForecastTimeSlot]]] = {
    val maybeForecastTimeSlots = handleUTCToBST(handleBSTToUTC(forecastPeriod.days))
      .toList
      .sortBy(_._1)
      .map(_._2.toList)

    Forecast.transposeIrregular(maybeForecastTimeSlots)
  }

  def timeSlotStartTimes(forecastPeriod: ForecastPeriod, millisToRowLabel: (MillisSinceEpoch) => String): Seq[String] =
    forecastPeriod.days.toList.find(_._2.length == Forecast.timeslotsOnBSTToUTCChangeDay) match {
      case Some((_, slots)) => slots.map(s => millisToRowLabel(s.startMillis))
      case None => forecastPeriod
        .days
        .headOption
        .map {
          case (_, slots) => slots.toList.map(s => millisToRowLabel(s.startMillis))
        }
        .getOrElse(List())

    }

  def rangeContainsBSTToUTCChange[A](daysOfForecastTimesSlots: Seq[(MillisSinceEpoch, Seq[A])]): Boolean =
    daysOfForecastTimesSlots.exists(_._2.size == timeslotsOnBSTToUTCChangeDay)


  def handleBSTToUTC(forecastPeriodDays: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]]): Map[MillisSinceEpoch, Seq[Option[ForecastTimeSlot]]] = {
    val epochToMaybeSlots: Map[MillisSinceEpoch, Seq[Option[ForecastTimeSlot]]] = forecastPeriodDays.mapValues(_.map(Option(_))).toMap
    if (rangeContainsBSTToUTCChange(epochToMaybeSlots.toList)) {
      epochToMaybeSlots.mapValues {
        case maybeTimeSlots if maybeTimeSlots.length == timeslotsOnRegualarDay =>
          maybeTimeSlots.take(8).toList ::: List(None, None, None, None) ::: maybeTimeSlots.drop(8).toList
        case m => m
      }.toMap
    } else epochToMaybeSlots
  }

  def rangeContainsUTCToBSTChange[A](daysOfForecastTimesSlots: Seq[(MillisSinceEpoch, Seq[A])]): Boolean =
    daysOfForecastTimesSlots.exists(_._2.size == timeslotsOnUTCToBSTChangeDay)

  def handleUTCToBST(epochToMaybeSlots: Map[MillisSinceEpoch, Seq[Option[ForecastTimeSlot]]]): Map[MillisSinceEpoch, Seq[Option[ForecastTimeSlot]]] = {
    if (rangeContainsUTCToBSTChange(epochToMaybeSlots.toList)) {
      epochToMaybeSlots.map {
        case (m, maybeTimeSlots) if maybeTimeSlots.length == timeslotsOnUTCToBSTChangeDay =>
          (m, maybeTimeSlots.take(4).toList ::: List(None, None, None, None) ::: maybeTimeSlots.drop(4).toList)
        case m => m
      }
    } else epochToMaybeSlots
  }
}
