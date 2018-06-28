package drt.chroma

import akka.NotUsed
import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, FeedResponse}
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.language.postfixOps

object StreamingChromaFlow {

  def chromaPollingSourceLive(log: LoggingAdapter, chromaFetcher: ChromaFetcher, pollFrequency: FiniteDuration): Source[FeedResponse, Cancellable] = {
    implicit val l = log
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Try[Seq[ChromaLiveFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => Try(chromaFetcher.currentFlightsBlocking))

    val recoverableTicking: Source[FeedResponse, Cancellable] = tickingSource
      .map {
        case Failure(f) => ArrivalsFeedFailure(f.toString, SDate.now())
        case Success(chromaFlights) => ArrivalsFeedSuccess(Flights(liveChromaToArrival(chromaFlights)), SDate.now())
      }
    recoverableTicking
  }

  def chromaPollingSourceForecast(log: LoggingAdapter, chromaFetcher: ChromaFetcherForecast, pollFrequency: FiniteDuration): Source[FeedResponse, Cancellable] = {
    implicit val l = log
    val initialDelayImmediately: FiniteDuration = 15 seconds
    val tickingSource: Source[Try[Seq[ChromaForecastFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => Try(chromaFetcher.currentFlightsBlocking))

    val recoverableTicking: Source[FeedResponse, Cancellable] = tickingSource
      .map {
        case Failure(f) => ArrivalsFeedFailure(f.toString, SDate.now())
        case Success(chromaFlights) => ArrivalsFeedSuccess(Flights(forecastChromaToArrival(chromaFlights)), SDate.now())
      }
    recoverableTicking
  }

  def liveChromaToArrival(chromaArrivals: Seq[ChromaLiveFlight]): List[Arrival] = {
      chromaArrivals.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        Arrival(
          Operator = flight.Operator,
          Status = flight.Status, EstDT = flight.EstDT,
          ActDT = flight.ActDT, EstChoxDT = flight.EstChoxDT,
          ActChoxDT = flight.ActChoxDT,
          Gate = flight.Gate,
          Stand = flight.Stand,
          MaxPax = flight.MaxPax,
          ActPax = flight.ActPax,
          TranPax = flight.TranPax,
          RunwayID = flight.RunwayID,
          BaggageReclaimId = flight.BaggageReclaimId,
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime,
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList
  }

  def forecastChromaToArrival(chromaArrivals: Seq[ChromaForecastFlight]): List[Arrival] = {
      chromaArrivals.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        Arrival(
          Operator = "",
          Status = "Port Forecast",
          EstDT = "",
          ActDT = "",
          EstChoxDT = "",
          ActChoxDT = "",
          Gate = "",
          Stand = "",
          MaxPax = 0,
          ActPax = flight.EstPax,
          TranPax = flight.EstTranPax,
          RunwayID = "",
          BaggageReclaimId = "",
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime,
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList
  }

}
