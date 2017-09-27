package services

import drt.shared.Crunch.MillisSinceEpoch
import drt.shared.FlightsApi._
import drt.shared._

import scala.concurrent.Future


trait FlightsService extends FlightsApi {
  def getFlightsWithSplits(st: Long, end: Long): Future[Either[FlightsNotReady, FlightsWithSplits]]
  def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]]

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
    getFlightsWithSplits(startTimeEpoch, endTimeEpoch)
  }
}



