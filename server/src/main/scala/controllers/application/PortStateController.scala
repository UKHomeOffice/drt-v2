package controllers.application

import actors.CrunchManagerActor.{RecalculateArrivals, Recrunch}
import actors.DateRange
import actors.PartitionedPortStateActor.{GetStateForDateRange, GetStateForTerminalDateRange, GetUpdatesSince, PointInTimeQuery}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import drt.shared.CrunchApi.{ForecastPeriodWithHeadlines, MillisSinceEpoch, PortStateUpdates}
import drt.shared.PortState
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.crunch.CrunchManager.{queueDaysToReCrunchWithUpdatedSplits, queueDaysToReProcess}
import services.exports.Forecast
import uk.gov.homeoffice.drt.auth.Roles.{DesksAndQueuesView, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
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

  def forecastWeekSummary(terminalName: String, startDay: MillisSinceEpoch, forecastPeriod: Int): Action[AnyContent] = authByRole(DesksAndQueuesView) {
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
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString} on $terminal")
            val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState, forecastPeriod)
            val hf = Forecast.headlineFigures(startOfForecast, numberOfDays, terminal, portState,
              airportConfig.queuesByTerminal(terminal).toList)
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

  def reCrunch(fromStr: String, toStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      val maybeFuture = for {
        from <- LocalDate.parse(fromStr)
        to <- LocalDate.parse(toStr)
      } yield {
        val datesToReCrunch = DateRange(from, to).map { localDate => SDate(localDate).millisSinceEpoch }.toSet
        ctrl.applicationService.crunchManagerActor ! datesToReCrunch
        Ok(s"Queued dates $from to $to for re-crunch")
      }
      maybeFuture.getOrElse(BadRequest(s"Unable to parse dates '$fromStr' or '$toStr'"))
    }
  }

  def reCrunchFullForecast: Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async { request: Request[AnyContent] =>
      request.body.asText match {
        case Some("true") =>
          queueDaysToReCrunchWithUpdatedSplits(ctrl.actorService.flightsRouterActor,
            ctrl.applicationService.crunchManagerActor,
            airportConfig.crunchOffsetMinutes,
            ctrl.params.forecastMaxDays, ctrl.now)
          Future.successful(Ok("Re-crunching with updated splits"))
        case _ =>
          queueDaysToReProcess(ctrl.applicationService.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now, m => Recrunch(m))
          Future.successful(Ok("Re-crunching without updating splits"))
      }
    }
  }

  def reCalculateArrivals: Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async { _ =>
      queueDaysToReProcess(ctrl.applicationService.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now, m => RecalculateArrivals(m))
      Future.successful(Ok("Re-calculating arrivals"))
    }
  }
}
