package drt.chroma

import akka.NotUsed
import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.springframework.util.StringUtils
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, ArrivalsFeedResponse}
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.language.postfixOps

object StreamingChromaFlow {

  def chromaPollingSourceLive(log: LoggingAdapter, chromaFetcher: ChromaFetcher, pollFrequency: FiniteDuration): Source[ArrivalsFeedResponse, Cancellable] = {
    implicit val l: LoggingAdapter = log
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Try[Seq[ChromaLiveFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => Try(chromaFetcher.currentFlightsBlocking))

    val recoverableTicking: Source[ArrivalsFeedResponse, Cancellable] = tickingSource
      .map {
        case Failure(f) => ArrivalsFeedFailure(f.toString, SDate.now())
        case Success(chromaFlights) => ArrivalsFeedSuccess(Flights(liveChromaToArrival(chromaFlights)), SDate.now())
      }
    recoverableTicking
  }

  def chromaPollingSourceForecast(log: LoggingAdapter, chromaFetcher: ChromaFetcherForecast, pollFrequency: FiniteDuration): Source[ArrivalsFeedResponse, Cancellable] = {
    implicit val l: LoggingAdapter = log
    val initialDelayImmediately: FiniteDuration = 15 seconds
    val tickingSource: Source[Try[Seq[ChromaForecastFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => Try(chromaFetcher.currentFlightsBlocking))

    val recoverableTicking: Source[ArrivalsFeedResponse, Cancellable] = tickingSource
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
        val est = Try(SDate(flight.EstDT).millisSinceEpoch).getOrElse(0L)
        val act = Try(SDate(flight.ActDT).millisSinceEpoch).getOrElse(0L)
        val estChox = Try(SDate(flight.EstChoxDT).millisSinceEpoch).getOrElse(0L)
        val actChox = Try(SDate(flight.ActChoxDT).millisSinceEpoch).getOrElse(0L)
        Arrival(
          Operator = if (StringUtils.isEmpty(flight.Operator)) None else Option(flight.Operator),
          Status = flight.Status,
          Estimated = if (est == 0) None else Option(est),
          Actual = if (act == 0) None else Option(act),
          EstimatedChox = if (estChox == 0) None else Option(estChox),
          ActualChox = if (actChox == 0) None else Option(actChox),
          Gate = if (StringUtils.isEmpty(flight.Gate)) None else Option(flight.Gate),
          Stand = if (StringUtils.isEmpty(flight.Stand)) None else Option(flight.Stand),
          MaxPax = if (flight.MaxPax == 0) None else Option(flight.MaxPax),
          ActPax = if (flight.ActPax == 0) None else Option(flight.ActPax),
          TranPax = if (flight.ActPax == 0) None else Option(flight.TranPax),
          RunwayID = if (StringUtils.isEmpty(flight.RunwayID)) None else Option(flight.RunwayID),
          BaggageReclaimId = if (StringUtils.isEmpty(flight.BaggageReclaimId)) None else Option(flight.BaggageReclaimId),
          FlightID = if (flight.FlightID == 0) None else Option(flight.FlightID),
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          PcpTime = Some(pcpTime),
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList
  }

  def forecastChromaToArrival(chromaArrivals: Seq[ChromaForecastFlight]): List[Arrival] = {
      chromaArrivals.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        Arrival(
          Operator = None,
          Status = "Port Forecast",
          Estimated = None,
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = None,
          ActPax = if (flight.EstPax == 0) None else Option(flight.EstPax),
          TranPax = if (flight.EstPax == 0) None else Option(flight.EstTranPax),
          RunwayID = None,
          BaggageReclaimId = None,
          FlightID = if (flight.FlightID == 0) None else Option(flight.FlightID),
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          rawICAO = flight.ICAO,
          rawIATA = flight.IATA,
          Origin = flight.Origin,
          PcpTime = Option(pcpTime),
          Scheduled = SDate(flight.SchDT).millisSinceEpoch
        )
      }).toList
  }

}
