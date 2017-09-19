package services

import drt.shared.FlightsApi._
import drt.shared._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


trait FlightsService extends FlightsApi {
  def getFlightsWithSplits(st: Long, end: Long): Future[Either[FlightsNotReady, FlightsWithSplits]]
  def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]]

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
    getFlightsWithSplits(startTimeEpoch, endTimeEpoch)
  }
}



