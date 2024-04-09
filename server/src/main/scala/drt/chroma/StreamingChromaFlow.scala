package drt.chroma

import akka.actor.typed
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaFlightLike, ChromaLiveFlight}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object StreamingChromaFlow {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def chromaPollingSource[X <: ChromaFlightLike](chromaFetcher: ChromaFetcher[X],
                                                 toDrtArrival: Seq[X] => List[FeedArrival],
                                                 source: Source[FeedTick, typed.ActorRef[FeedTick]])
                                                (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    source.mapAsync(1) { _ =>
      chromaFetcher.currentFlights
        .map {
          case Success(flights) => ArrivalsFeedSuccess(toDrtArrival(flights))
          case Failure(t) => ArrivalsFeedFailure(t.getMessage)
        }
        .recoverWith { case t =>
          Future(ArrivalsFeedFailure(t.getMessage))
        }
    }
  }

  def liveChromaToFeedArrival(chromaArrivals: Seq[ChromaLiveFlight]): List[FeedArrival] = {
    chromaArrivals.map(flight => {
      val est = Try(SDate(flight.EstDT).millisSinceEpoch).getOrElse(0L)
      val act = Try(SDate(flight.ActDT).millisSinceEpoch).getOrElse(0L)
      val estChox = Try(SDate(flight.EstChoxDT).millisSinceEpoch).getOrElse(0L)
      val actChox = Try(SDate(flight.ActChoxDT).millisSinceEpoch).getOrElse(0L)
      val (carrierCode, voyageNumber, maybeSuffix) = FlightCode.flightCodeToParts(if (flight.ICAO != "") flight.ICAO else flight.IATA)
      LiveArrival(
        operator = if (flight.Operator.isEmpty) None else Option(flight.Operator),
        maxPax = if (flight.MaxPax == 0) None else Option(flight.MaxPax),
        totalPax = if (flight.ActPax == 0) None else Option(flight.ActPax),
        transPax = if (flight.TranPax == 0) None else Option(flight.TranPax),
        terminal = Terminal(flight.Terminal),
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = maybeSuffix.map(_.suffix),
        origin = flight.Origin,
        scheduled = SDate(flight.SchDT).millisSinceEpoch,
        estimated = if (est == 0) None else Option(est),
        touchdown = if (act == 0) None else Option(act),
        estimatedChox = if (estChox == 0) None else Option(estChox),
        actualChox = if (actChox == 0) None else Option(actChox),
        status = flight.Status,
        gate = if (flight.Gate.isBlank) None else Option(flight.Gate),
        stand = if (flight.Stand.isBlank) None else Option(flight.Stand),
        runway = if (flight.RunwayID.isBlank) None else Option(flight.RunwayID),
        baggageReclaim = if (flight.BaggageReclaimId.isBlank) None else Option(flight.BaggageReclaimId),
      )
    }).toList
  }
}
