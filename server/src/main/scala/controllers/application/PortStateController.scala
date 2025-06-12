package controllers.application

import actors.CrunchManagerActor._
import actors.PartitionedPortStateActor.{GetStateForDateRange, GetStateForTerminalDateRange, GetUpdatesSince, PointInTimeQuery}
import com.google.inject.Inject
import drt.shared.CrunchApi.{ForecastPeriodWithHeadlines, MillisSinceEpoch, PortStateUpdates}
import drt.shared.{CrunchApi, PortState}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.crunch.CrunchManager.{queueDaysToReCrunchWithUpdatedSplits, queueDaysToReProcess}
import services.exports.Forecast
import uk.gov.homeoffice.drt.auth.Roles.{DesksAndQueuesView, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateRange, LocalDate, SDate, SDateLike}
import upickle.default.write

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt


class PortStateController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getCrunch: Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val maybeSinceMillis =
        for {
          flightsSince <- request.queryString.get("flights-since").flatMap(_.headOption.map(_.toLong))
          queuesSince <- request.queryString.get("queues-since").flatMap(_.headOption.map(_.toLong))
          staffSince <- request.queryString.get("staff-since").flatMap(_.headOption.map(_.toLong))
        } yield (flightsSince, queuesSince, staffSince)

      val eventualUpdates = maybeSinceMillis match {
        case None =>
          portStateForDates(startMillis, endMillis).map(r => Ok(write(r)))
        case Some((flights, queues, staff)) =>
          portStateUpdatesForRange(startMillis, endMillis, flights, queues, staff).map(r => Ok(write(r)))
      }

      eventualUpdates
        .recoverWith {
          case t =>
            log.error(t, "Error processing request for port state or port state updates")
            Future(InternalServerError)
        }
    }
  }

  private def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }

  def forecastWeekSummary(terminalName: String, startDay: MillisSinceEpoch, periodInterval: Int): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async {
      val terminal = Terminal(terminalName)
      val numberOfDays = 7
      val (startOfForecast, endOfForecast) = startAndEndForDay(startDay, numberOfDays)

      val portStateFuture = ctrl.actorService.portStateActor.ask(
        GetStateForTerminalDateRange(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
      )(new Timeout(30.seconds))

      val forecast = portStateFuture
        .map {
          case portState: PortState =>
            val queues = ctrl.applicationService.queuesForDateRangeAndTerminal
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString} on $terminal")
            val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState, periodInterval)
            val hf: CrunchApi.ForecastHeadlineFigures = Forecast.headlineFigures(startOfForecast, numberOfDays, terminal, portState, queues)
            Option(ForecastPeriodWithHeadlines(fp, hf))
        }
        .recover {
          case t =>
            log.error(s"Failed to get PortState: ${t.getMessage}")
            None
        }
      forecast.map(r => Ok(write(r)))
    }
  }

  private def portStateUpdatesForRange(startMillis: MillisSinceEpoch,
                                       endMillis: MillisSinceEpoch,
                                       flightsSince: MillisSinceEpoch,
                                       queuesSince: MillisSinceEpoch,
                                       staffSince: MillisSinceEpoch,
                                      ): Future[Option[PortStateUpdates]] =
    ctrl.actorService.portStateActor
      .ask(GetUpdatesSince(flightsSince, queuesSince, staffSince, startMillis, endMillis))
      .mapTo[Option[PortStateUpdates]]

  private def portStateForDates(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): Future[PortState] =
    ctrl.actorService.portStateActor
      .ask(GetStateForDateRange(startMillis, endMillis))
      .mapTo[PortState]

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val futureState = ctrl.actorService.portStateActor
        .ask(PointInTimeQuery(pointInTime, GetStateForDateRange(startMillis, endMillis)))(new Timeout(90.seconds))
        .mapTo[PortState]

      futureState
        .map { updates => Ok(write(updates)) }
        .recoverWith {
          case t =>
            log.error(t, "Error processing request for port state")
            Future(InternalServerError)
        }
    }
  }

  private val forecastLengthDays: Int = ctrl.params.forecastMaxDays
  private val now: () => SDateLike = ctrl.now
  private val offsetMinutes: Int = airportConfig.crunchOffsetMinutes
  private val crunchManagerActor: ActorRef = ctrl.applicationService.crunchManagerActor

  def reCrunch(fromStr: String, toStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      val maybeFuture = for {
        from <- LocalDate.parse(fromStr)
        to <- LocalDate.parse(toStr)
      } yield {
        val datesToReCrunch = DateRange(from, to).map { localDate => SDate(localDate).millisSinceEpoch }.toSet
        crunchManagerActor ! Recrunch(datesToReCrunch)
        Ok(s"Queued dates $from to $to for re-crunch")
      }
      maybeFuture.getOrElse(BadRequest(s"Unable to parse dates '$fromStr' or '$toStr'"))
    }
  }

  def reCrunchFullForecast: Action[AnyContent] = authByRole(SuperAdmin) {
    Action { request: Request[AnyContent] =>
      request.body.asText match {
        case Some("true") =>
          queueDaysToReCrunchWithUpdatedSplits(
            ctrl.actorService.flightsRouterActor,
            crunchManagerActor,
            offsetMinutes,
            forecastLengthDays,
            now
          )
          Ok("Re-crunching with updated splits")
        case _ =>
          queueDaysToReProcess(crunchManagerActor, offsetMinutes, forecastLengthDays, now, m => Recrunch(m))
          Ok("Re-crunching without updating splits")
      }
    }
  }

  def recalculateSplits: Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      queueDaysToReProcess(crunchManagerActor, offsetMinutes, 1, now, m => RecalculateLiveSplits(m))
      queueDaysToReProcess(crunchManagerActor, offsetMinutes, forecastLengthDays, now, m => RecalculateHistoricSplits(m))
      Ok("Recalculating live splits")
    }
  }

  def lookupMissingHistoricSplits: Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      queueDaysToReProcess(crunchManagerActor, offsetMinutes, forecastLengthDays, now, m => LookupHistoricSplits(m))
      Ok("Re-crunching without updating splits")
    }
  }

  def lookupMissingPaxNos: Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      queueDaysToReProcess(crunchManagerActor, offsetMinutes, forecastLengthDays, now, m => LookupHistoricPaxNos(m))
      Ok("Re-crunching without updating splits")
    }
  }

  def reCalculateArrivals: Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      queueDaysToReProcess(crunchManagerActor, offsetMinutes, forecastLengthDays, now, m => RecalculateArrivals(m))
      Ok("Re-calculating arrivals")
    }
  }
}
