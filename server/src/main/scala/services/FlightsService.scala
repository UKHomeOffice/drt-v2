package services

import drt.shared.FlightsApi._
import drt.shared._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[Arrival]]
  def getFlightsWithSplits(st: Long, end: Long): Future[Either[FlightsNotReady, FlightsWithSplits]]
  def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
    getFlightsWithSplits(startTimeEpoch, endTimeEpoch)
  }
}



