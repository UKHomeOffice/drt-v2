package drt.client.services

import autowire._
import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.actions.Actions._
import drt.client.components.LoadingState
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.{TerminalName, _}
import drt.shared._

import boopickle.Default._ //Don't remove this import

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

case class TimeRangeHours(start: Int = 0, end: Int = 24)

case class RootModel(
                      crunchStatePot: Pot[CrunchState] = Empty,
                      airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                      airportConfig: Pot[AirportConfig] = Empty,
                      shiftsRaw: Pot[String] = Empty,
                      fixedPointsRaw: Pot[String] = Empty,
                      staffMovements: Pot[Seq[StaffMovement]] = Empty,
                      pointInTime: Option[SDateLike] = None,
                      timeRangeFilter: TimeRangeHours = TimeRangeHours(),
                      loadingState: LoadingState = LoadingState()
                    )


abstract class LoggingActionHandler[M, T](modelRW: ModelRW[M, T]) extends ActionHandler(modelRW) {
  override def handleAction(model: M, action: Any): Option[ActionResult[M]] = {
    Try(super.handleAction(model, action)) match {
      case Failure(f) =>
        f.getCause() match {
          case null => log.error(s"no cause")
          case c => log.error(s"Exception from $getClass  ${c.getMessage}")
        }

        throw f
      case Success(s) =>
        s
    }
  }
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case _: GetAirportConfig =>
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig)))
    case UpdateAirportConfig(configHolder) =>
      updated(Ready(configHolder))
  }
}

class CrunchStateHandler[M](pointInTime: ModelR[M, Option[SDateLike]], modelRW: ModelRW[M, Pot[CrunchState]]) extends LoggingActionHandler(modelRW) {
  val crunchStateRequestFrequency = 10 seconds
  val reRequestDelay = 20 seconds

  def pointInTimeMillis = pointInTime.value.map(m => {
    m.millisSinceEpoch
  }).getOrElse(0L)

  protected def handle = {
    case GetCrunchState() =>
      log.info(s"Requesting crunchState for point in time ${SDate(MilliDate(pointInTimeMillis)).toLocalDateTimeString} - $pointInTime")
      val x = AjaxClient[Api].getCrunchState(pointInTimeMillis).call().map {
        case Some(cs) =>
          log.info(s"Got ${cs.flights.size} flights!")
          UpdateCrunchStateAndContinuePolling(cs)
        case None =>
          log.info(s"CrunchState not ready")
          GetCrunchStateAfter(reRequestDelay)
      }
      effectOnly(Effect(Future(ShowLoader("Asking for new flights..."))) + Effect(x))

    case UpdateCrunchStateAndContinuePolling(crunchState: CrunchState) =>
      val getCrunchStateAfterDelay = Effect(Future(GetCrunchState())).after(crunchStateRequestFrequency)
      val updateCrunchState = Effect(Future(UpdateCrunchState(crunchState)))
      effectOnly(updateCrunchState + getCrunchStateAfterDelay)

    case GetCrunchStateAfter(delay) =>
      log.info(s"Re-requesting flights")
      val getCrunchStateAfterDelay = Effect(Future(GetCrunchState())).after(delay)
      effectOnly(getCrunchStateAfterDelay)

    case UpdateCrunchState(crunchState) =>
      log.info(s"client got ${crunchState.flights.size} flights (from CrunchState)")

      updated(Ready(crunchState), Effect(Future(HideLoader())))
  }
}

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle = {
    case GetAirportInfos(codes) =>
      val stringToObject: Map[String, Pot[AirportInfo]] = value ++ Map("BHX" -> mkPending, "EDI" -> mkPending)
      updated(stringToObject, Effect(AjaxClient[Api].airportInfosByAirportCodes(codes).call().map(UpdateAirportInfos)))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case GetAirportInfo(code) =>
      value.get(code) match {
        case None =>
          val stringToObject = value + (code -> Empty)
          updated(stringToObject, Effect(AjaxClient[Api].airportInfoByAirportCode(code).call().map(res => UpdateAirportInfo(code, res))))
        case Some(v) =>
          noChange
      }
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + (code -> Ready(airportInfo))
      updated(newValue)
  }
}

class ShiftsHandler[M](pointInTime: ModelR[M, Option[SDateLike]], modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetShifts(shifts: String) =>
      updated(Ready(shifts))

    case AddShift(shift) =>
      updated(Ready(s"${value.getOrElse("")}\n${shift.toCsv}"))

    case GetShifts() =>
      val shiftsEffect = Effect(Future(GetShifts())).after(300 seconds)

      def pointInTimeMillis = pointInTime.value.map(_.millisSinceEpoch).getOrElse(0L)

      val apiCallEffect = Effect(AjaxClient[Api].getShifts(pointInTimeMillis).call().map(res => SetShifts(res)))
      effectOnly(apiCallEffect + shiftsEffect)
  }
}

object FixedPoints {
  def filterTerminal(terminalName: TerminalName, rawFixedPoints: String): String = {
    rawFixedPoints.split("\n").toList.filter(line => {
      val terminal = line.split(",").toList.map(_.trim) match {
        case _ :: t :: _ => t
        case _ => Nil
      }
      terminal == terminalName
    }).mkString("\n")
  }

  def filterOtherTerminals(terminalName: TerminalName, rawFixedPoints: String): String = {
    rawFixedPoints.split("\n").toList.filter(line => {
      val terminal = line.split(",").toList.map(_.trim) match {
        case _ :: t :: _ => t
        case _ => Nil
      }
      terminal != terminalName
    }).mkString("\n")
  }

  def removeTerminalNameAndDate(rawFixedPoints: String) = {
    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withTerminal = line.split(",").toList.map(_.trim)
      val withOutTerminal = withTerminal match {
        case fpName :: terminal :: date :: tail => fpName.toString :: tail
        case _ => Nil
      }
      withOutTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }

  def addTerminalNameAndDate(rawFixedPoints: String, terminalName: String) = {
    val today: SDateLike = SDate.today
    val todayString = today.ddMMyyString

    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withoutTerminal = line.split(",").toList.map(_.trim)
      val withTerminal = withoutTerminal match {
        case fpName :: tail => fpName.toString :: terminalName :: todayString :: tail
        case _ => Nil
      }
      withTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }
}

class FixedPointsHandler[M](pointInTime: ModelR[M, Option[SDateLike]], modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle = {
    case SetFixedPoints(fixedPoints: String, terminalName: Option[String]) =>
      if (terminalName.isDefined)
        updated(Ready(fixedPoints))
      else
        updated(Ready(fixedPoints))
    case SaveFixedPoints(fixedPoints: String, terminalName: TerminalName) =>
      val otherTerminalFixedPoints = FixedPoints.filterOtherTerminals(terminalName, value.getOrElse(""))
      val newRawFixedPoints = otherTerminalFixedPoints + "\n" + fixedPoints
      AjaxClient[Api].saveFixedPoints(newRawFixedPoints).call()
      effectOnly(Effect(Future(SetFixedPoints(newRawFixedPoints, Option(terminalName)))))
    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))
    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)

      def pointInTimeMillis = pointInTime.value.map(_.millisSinceEpoch).getOrElse(0L)

      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints(pointInTimeMillis).call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}

class StaffMovementsHandler[M](pointInTime: ModelR[M, Option[SDateLike]], modelRW: ModelRW[M, Pot[Seq[StaffMovement]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovement(staffMovement) =>
      if (value.isReady) {
        val v: Seq[StaffMovement] = value.get
        val updatedValue: Seq[StaffMovement] = (v :+ staffMovement).sortBy(_.time)
        updated(Ready(updatedValue))
      } else noChange
    case RemoveStaffMovement(idx, uUID) =>
      if (value.isReady) {
        val terminalAffectedOption = value.get.find(_.uUID == uUID).map(_.terminalName)
        if (terminalAffectedOption.isDefined) {
          val updatedValue = value.get.filter(_.uUID != uUID)
          updated(Ready(updatedValue), Effect(Future(SaveStaffMovements(terminalAffectedOption.get))))
        } else noChange
      } else noChange
    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      updated(Ready(staffMovements))
    case GetStaffMovements() =>
      val movementsEffect = Effect(Future(GetStaffMovements())).after(60 seconds)

      def pointInTimeMillis = pointInTime.value.map(_.millisSinceEpoch).getOrElse(0L)

      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements(pointInTimeMillis).call().map(res => SetStaffMovements(res)))
      effectOnly(apiCallEffect + movementsEffect)
    case SaveStaffMovements(terminalName) =>
      if (value.isReady) {
        AjaxClient[Api].saveStaffMovements(value.get).call()
        noChange
      } else noChange
  }
}

class PointInTimeHandler[M](modelRW: ModelRW[M, Option[SDateLike]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetPointInTime(pointInTime) =>
      log.info(s"Set client point in time: $pointInTime")
      val sdatePointInTime = SDate(MilliDate(pointInTime))
      val nextRequests = Effect(Future(GetCrunchState())) + Effect(Future(GetShifts())) +
        Effect(Future(GetFixedPoints())) + Effect(Future(GetStaffMovements()))
      updated(Option(sdatePointInTime), nextRequests)

    case SetPointInTimeToLive() =>
      log.info(s"Set client point in time to live")
      val nextRequest = Effect(Future(GetCrunchState()))
      updated(None, nextRequest)
  }
}

class TimeRangeFilterHandler[M](modelRW: ModelRW[M, TimeRangeHours]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetTimeRangeFilter(r) =>
      updated(r)
  }
}

class LoaderHandler[M](modelRW: ModelRW[M, LoadingState]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ShowLoader(message) =>
      updated(LoadingState(isLoading = true, message))
    case HideLoader() =>
      updated(LoadingState(isLoading = false, ""))
  }
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider() = new Date().getTime.toLong

  override protected def initialModel = RootModel()

  override val actionHandler = {
    val composedhandlers: HandlerFunction = composeHandlers(
      new CrunchStateHandler(zoom(_.pointInTime), zoomRW(_.crunchStatePot)((m, v) => m.copy(crunchStatePot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ShiftsHandler(zoom(_.pointInTime), zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new FixedPointsHandler(zoom(_.pointInTime), zoomRW(_.fixedPointsRaw)((m, v) => m.copy(fixedPointsRaw = v))),
      new StaffMovementsHandler(zoom(_.pointInTime), zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new PointInTimeHandler(zoomRW(_.pointInTime)((m, v) => m.copy(pointInTime = v))),
      new TimeRangeFilterHandler(zoomRW(_.timeRangeFilter)((m, v) => m.copy(timeRangeFilter = v))),
      new LoaderHandler(zoomRW(_.loadingState)((m, v) => m.copy(loadingState = v)))
    )

    val loggedhandlers: HandlerFunction = (model, update) => {
      log.debug(s"functional handler for ${update.toString.take(100)}")
      composedhandlers(model, update)
    }

    loggedhandlers
  }

  def pointInTimeMillis: Long = {
    zoom(_.pointInTime).value.map(_.millisSinceEpoch).getOrElse(0L)
  }
}

object SPACircuit extends DrtCircuit
