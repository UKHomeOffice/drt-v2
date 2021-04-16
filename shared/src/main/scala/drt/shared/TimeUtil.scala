package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch

object TimeUtil {
  def millisToMinutes(millis: MillisSinceEpoch): Int = {
    val inSeconds = millis / 1000
    val minutes = inSeconds / 60
    minutes.toInt
  }
}
