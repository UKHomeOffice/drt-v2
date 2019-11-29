package drt.chroma

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaFlightLike, ChromaForecastFlight, ChromaLiveFlight}
import drt.server.feeds.Implicits._
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.Terminal
import drt.shared.{Arrival, ForecastFeedSource, LiveFeedSource}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.util.StringUtils
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object StreamingChromaFlow {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def chromaPollingSource[X <: ChromaFlightLike](chromaFetcher: ChromaFetcher[X],
                                                 pollFrequency: FiniteDuration,
                                                 toDrtArrival: Seq[X] => List[Arrival])(implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    Source.tick(1 milliseconds, pollFrequency, NotUsed)
      .mapAsync(1) { _ =>
        chromaFetcher.currentFlights
          .map {
            case Success(flights) => ArrivalsFeedSuccess(Flights(toDrtArrival(flights)))
            case Failure(t) => ArrivalsFeedFailure(t.getMessage)
          }
          .recoverWith { case t =>
            Future(ArrivalsFeedFailure(t.getMessage))
          }
      }
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
        AirportID = flight.AirportID,
        Terminal = Terminal(flight.Terminal),
        rawICAO = flight.ICAO,
        rawIATA = flight.IATA,
        Origin = flight.Origin,
        PcpTime = Some(pcpTime),
        Scheduled = SDate(flight.SchDT).millisSinceEpoch,
        FeedSources = Set(LiveFeedSource)
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
        AirportID = flight.AirportID,
        Terminal = Terminal(flight.Terminal),
        rawICAO = flight.ICAO,
        rawIATA = flight.IATA,
        Origin = flight.Origin,
        PcpTime = Option(pcpTime),
        FeedSources = Set(ForecastFeedSource),
        Scheduled = SDate(flight.SchDT).millisSinceEpoch
      )
    }).toList
  }

}
