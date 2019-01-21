package drt.server.feeds.lhr

import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.server.feeds.lhr.forecast.LHRForecastFlightRow
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, ForecastFeedSource}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, GetFeedImportArrivals}
import services.SDate

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.Try

case class LHRForecastFeed(arrivalsActor: AskableActorRef) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def requestFeed: ArrivalsFeedResponse = {
    val futureArrivals = arrivalsActor.ask(GetFeedImportArrivals)(new Timeout(10 seconds))
    val futureResponse = futureArrivals
      .map {
        case Some(Flights(arrivals)) =>
          log.info(s"Got ${arrivals.length} LHR port forecast arrivals")
          ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
        case x =>
          log.info(s"Got no LHR port forecast arrivals: $x")
          ArrivalsFeedSuccess(Flights(Seq()), SDate.now())
      }
      .recoverWith {
        case e => Future(ArrivalsFeedFailure(e.getMessage, SDate.now()))
      }

    Await.result(futureResponse, 10 seconds)
  }
}

object LHRForecastFeed {
  def log: Logger = LoggerFactory.getLogger(classOf[LHRForecastFeed])

  def lhrFieldsToArrival(flightRow: LHRForecastFlightRow): Try[Arrival] = {
    Try {
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
        ActPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax),
        TranPax = if (flightRow.totalPax == 0) None else Option(flightRow.transferPax),
        RunwayID = None,
        BaggageReclaimId = None,
        FlightID = None,
        AirportID = "LHR",
        Terminal = flightRow.terminal,
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = flightRow.origin,
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        None
      )
    }
  }
}
