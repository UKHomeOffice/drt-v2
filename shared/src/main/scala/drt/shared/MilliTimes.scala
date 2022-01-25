//package drt.shared
//
//import drt.shared.CrunchApi.MillisSinceEpoch
//
//import java.lang.Math.round
//
//object MilliTimes {
//  val oneSecondMillis: Int = 1000
//  val oneMinuteMillis: Int = 60 * oneSecondMillis
//  val oneHourMillis: Int = 60 * oneMinuteMillis
//  val oneDayMillis: Int = 24 * oneHourMillis
//  val minutesInADay: Int = 60 * 24
//
//  def timeToNearestMinute(t: MillisSinceEpoch): MillisSinceEpoch = round(t / 60000d) * 60000
//
//  val fifteenMinutesMillis: Int = oneMinuteMillis * 15
//  val fifteenMinuteSlotsInDay: Int = 4 * 24
//}
