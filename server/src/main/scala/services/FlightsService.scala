package services

import drt.shared.FlightsApi._
import drt.shared._

import scala.collection.immutable._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]
  def getFlightsWithSplits(st: Long, end: Long): Future[FlightsWithSplits]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[FlightsWithSplits] = {
    getFlightsWithSplits(startTimeEpoch, endTimeEpoch)
  }
}



