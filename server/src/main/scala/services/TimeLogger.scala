package services

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.Logger

case class TimeLogger(actionName: String, threshold: MillisSinceEpoch, logger: Logger) {
  def time[R](action: => R): R = {
    val startTime = System.currentTimeMillis()
    val result = action
    val timeTaken = System.currentTimeMillis() - startTime

    val message = s"$actionName took ${timeTaken}ms"

    if (timeTaken > threshold)
      logger.warn(message)
    else
      logger.debug(message)

    result
  }
}
