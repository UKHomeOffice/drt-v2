
package services

import actors.DrtSystemInterface
import actors.PartitionedPortStateActor.GetStateForTerminalDateRange
import actors.persistent.staffing.{GetState, UpdateShifts}
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
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
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

  def getShowAlertModalDialog(): Boolean
}

class ApiService(airportConfig: AirportConfig,
                 shiftsActorRef: ActorRef,
                 headers: Headers,
                 session: Session) extends ApiServiceI {

  implicit val system: ActorSystem = DrtActorSystem.actorSystem
  implicit val mat: Materializer = DrtActorSystem.mat
  implicit val ec: ExecutionContext = DrtActorSystem.ec
  val ctrl: DrtSystemInterface = DrtActorSystem.drtSystem
  val config: Configuration = DrtActorSystem.config

  override def shiftsActor: ActorRef = shiftsActorRef

  override def actorSystem: ActorSystem = DrtActorSystem.actorSystem

  def getLoggedInUser(): LoggedInUser =
    ctrl.getLoggedInUser(config, headers, session)


  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: Terminal): Future[Option[ForecastPeriodWithHeadlines]] = {
    val numberOfDays = 7
    val (startOfForecast, _) = startAndEndForDay(startDay, numberOfDays)

    val eventualHeadlines = (0 until 7).map { day =>
      val startMillis = startOfForecast.addDays(day).millisSinceEpoch
      val endMillis = startOfForecast.addDays(day + 1).addMinutes(-1).millisSinceEpoch
      ctrl.queuesRouterActor.ask(GetStateForTerminalDateRange(startMillis, endMillis, terminal))
        .mapTo[MinutesContainer[CrunchMinute, TQM]]
        .map { container =>
          container.minutes.map(x => x.toMinute)
            .groupBy(_.queue)
            .view.mapValues(minutes => (minutes.map(_.paxLoad).sum, minutes.map(_.workLoad).sum)).toMap
        }
        .recover {
          case t =>
            log.error(s"Failed to get PortState", t)
            Map[Queues.Queue, (Double, Double)]()
        }
        .map(_.map { case (queue, (pax, work)) => QueueHeadline(startMillis, queue, pax.toInt, work.toInt) })
    }
    Future.sequence(eventualHeadlines).map { headline =>
        Option(ForecastPeriodWithHeadlines(
          ForecastPeriod(Map()),
          ForecastHeadlineFigures(headline.flatten)
        ))
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

  def getShowAlertModalDialog(): Boolean = config
    .getOptional[Boolean]("feature-flags.display-modal-alert")
    .getOrElse(false)

  override def portStateActor: ActorRef = ctrl.portStateActor

}
