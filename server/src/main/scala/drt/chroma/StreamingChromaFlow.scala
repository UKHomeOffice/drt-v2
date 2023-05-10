package drt.chroma

import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaFlightLike, ChromaForecastFlight, ChromaLiveFlight}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.Implicits._
import drt.shared.FlightsApi.Flights
import org.apache.commons.lang3.StringUtils
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, Operator, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object StreamingChromaFlow {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def chromaPollingSource[X <: ChromaFlightLike](chromaFetcher: ChromaFetcher[X],
                                                 toDrtArrival: Seq[X] => List[Arrival],
                                                 source: Source[FeedTick, typed.ActorRef[FeedTick]])
                                                (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    source.mapAsync(1) { _ =>
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
        Operator = if (flight.Operator.isEmpty) None else Option(Operator(flight.Operator)),
        Status = flight.Status,
        Estimated = if (est == 0) None else Option(est),
        Predictions = Predictions(0L, Map()),
        Actual = if (act == 0) None else Option(act),
        EstimatedChox = if (estChox == 0) None else Option(estChox),
        ActualChox = if (actChox == 0) None else Option(actChox),
        Gate = if (StringUtils.isBlank(flight.Gate)) None else Option(flight.Gate),
        Stand = if (StringUtils.isBlank(flight.Stand)) None else Option(flight.Stand),
        MaxPax = if (flight.MaxPax == 0) None else Option(flight.MaxPax),
        RunwayID = if (StringUtils.isBlank(flight.RunwayID)) None else Option(flight.RunwayID),
        BaggageReclaimId = if (StringUtils.isBlank(flight.BaggageReclaimId)) None else Option(flight.BaggageReclaimId),
        AirportID = flight.AirportID,
        Terminal = Terminal(flight.Terminal),
        rawICAO = flight.ICAO,
        rawIATA = flight.IATA,
        Origin = flight.Origin,
        PcpTime = Some(pcpTime),
        Scheduled = SDate(flight.SchDT).millisSinceEpoch,
        FeedSources = Set(LiveFeedSource),
        PassengerSources = Map(LiveFeedSource -> Passengers(if (flight.ActPax == 0) None else Option(flight.ActPax), if (flight.ActPax == 0) None else Option(flight.TranPax)))
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
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = flight.AirportID,
        Terminal = Terminal(flight.Terminal),
        rawICAO = flight.ICAO,
        rawIATA = flight.IATA,
        Origin = flight.Origin,
        PcpTime = Option(pcpTime),
        FeedSources = Set(ForecastFeedSource),
        Scheduled = SDate(flight.SchDT).millisSinceEpoch,
        PassengerSources = Map(ForecastFeedSource -> Passengers(if (flight.EstPax == 0) None else Option(flight.EstPax), if (flight.EstPax == 0) None else Option(flight.EstTranPax)))
      )
    }).toList
  }

}
