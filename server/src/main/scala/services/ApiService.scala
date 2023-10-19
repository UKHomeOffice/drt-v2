
package services

import actors.DrtSystemInterface
import actors.PartitionedPortStateActor.GetStateForTerminalDateRange
import actors.persistent.staffing.UpdateShifts
import akka.actor._
import akka.pattern._
import akka.stream._
import akka.util.Timeout
import controllers.{DrtActorSystem, ShiftPersistence}
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc._
import services.graphstages.Crunch
import services.staffing.StaffTimeSlots
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait ApiServiceI extends Api with ShiftPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(30 seconds)

  override val log: Logger = LoggerFactory.getLogger(this.getClass)

  def portStateActor: ActorRef

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: Terminal): Future[Option[ForecastPeriodWithHeadlines]]

  def getShiftsForMonth(month: MillisSinceEpoch, terminal: Terminal): Future[ShiftAssignments]

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit
}

class ApiService(airportConfig: AirportConfig,
                 shiftsActorRef: ActorRef,
                 headers: Headers,
                 session: Session,
                 ctrl: DrtSystemInterface) extends ApiServiceI {

  implicit val system: ActorSystem = ctrl.system
  implicit val mat: Materializer = ctrl.materializer
  implicit val ec: ExecutionContext = ctrl.ec
  val config: Configuration = DrtActorSystem.config

  override def shiftsActor: ActorRef = shiftsActorRef

  override def actorSystem: ActorSystem = ctrl.system

  def getLoggedInUser(): LoggedInUser =
    ctrl.getLoggedInUser(config, headers, session)


  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: Terminal):
  Future[Option[ForecastPeriodWithHeadlines]] = {
    val numberOfDays = 7
    val (startOfForecast, endOfForecast) = startAndEndForDay(startDay, numberOfDays)

    val portStateFuture = portStateActor.ask(
      GetStateForTerminalDateRange(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
    )(new Timeout(30 seconds))

    portStateFuture
      .map {
        case portState: PortState =>
          log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString} on $terminal")
          val fp = services.exports.Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
          val hf = services.exports.Forecast.headlineFigures(startOfForecast, numberOfDays, terminal, portState,
            airportConfig.queuesByTerminal(terminal).toList)
          Option(ForecastPeriodWithHeadlines(fp, hf))
      }
      .recover {
        case t =>
          log.error(s"Failed to get PortState", t)
          None
      }
  }

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit =
    if (getLoggedInUser().roles.contains(StaffEdit)) {
      log.info(s"Saving ${shiftsToUpdate.length} shift staff assignments")
      shiftsActor ! UpdateShifts(shiftsToUpdate)
    } else {
      throw new Exception("You do not have permission to edit staffing.")
    }


  def getShiftsForMonth(month: MillisSinceEpoch, terminal: Terminal): Future[ShiftAssignments] = {
    val shiftsFuture = shiftsActor ? GetState

    shiftsFuture.collect {
      case shifts: ShiftAssignments =>
        log.info(s"Shifts: Retrieved shifts from actor for month starting: ${SDate(month).toISOString}")
        val monthInLocalTime = SDate(month, Crunch.europeLondonTimeZone)
        StaffTimeSlots.getShiftsForMonth(shifts, monthInLocalTime)
    }
  }

  override def portStateActor: ActorRef = ctrl.portStateActor
}
