package drt.client.services

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.TerminalName
import drt.shared._

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

sealed trait TimeRangeHours {
  def start: Int

  def end: Int
}

case class CustomWindow(start: Int, end: Int) extends TimeRangeHours

case class WholeDayWindow() extends TimeRangeHours {
  override def start: Int = 0

  override def end: Int = 24
}

case class CurrentWindow() extends TimeRangeHours {
  override def start: Int = SDate.now().getHours() - 1

  override def end: Int = SDate.now().getHours() + 3
}

sealed trait ViewMode {
  def millis: MillisSinceEpoch = time.millisSinceEpoch

  def time: SDateLike
}

case class ViewLive() extends ViewMode {
  def time: SDateLike = SDate.now()
}

case class ViewPointInTime(time: SDateLike) extends ViewMode

case class ViewDay(time: SDateLike) extends ViewMode

case class LoadingState(isLoading: Boolean = false)

case class RootModel(latestUpdateMillis: MillisSinceEpoch = 0L,
                     crunchStatePot: Pot[CrunchState] = Empty,
                     forecastPeriodPot: Pot[ForecastPeriodWithHeadlines] = Empty,
                     airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                     airportConfig: Pot[AirportConfig] = Empty,
                     shiftsRaw: Pot[String] = Empty,
                     fixedPointsRaw: Pot[String] = Empty,
                     staffMovements: Pot[Seq[StaffMovement]] = Empty,
                     viewMode: ViewMode = ViewLive(),
                     timeRangeFilter: TimeRangeHours = CurrentWindow(),
                     loadingState: LoadingState = LoadingState(),
                     showActualIfAvailable: Boolean = true
                    )

abstract class LoggingActionHandler[M, T](modelRW: ModelRW[M, T]) extends ActionHandler(modelRW) {
  override def handleAction(model: M, action: Any): Option[ActionResult[M]] = {
    Try(super.handleAction(model, action)) match {
      case Failure(f) =>
        f.getCause match {
          case null => log.error(s"no cause")
          case c => log.error(s"Exception from $getClass  ${c.getMessage}")
        }

        throw f
      case Success(s) =>
        s
    }
  }
}

object PollDelay {
  val recoveryDelay: FiniteDuration = 10 seconds
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case _: GetAirportConfig =>
      log.info(s"Calling airportConfiguration")
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig).recoverWith {
        case f =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(GetAirportConfigAfter(PollDelay.recoveryDelay))
      }))
    case UpdateAirportConfig(configHolder) =>
      updated(Ready(configHolder))
    case GetAirportConfigAfter(delay) =>
      effectOnly(Effect(Future(GetAirportConfig())).after(delay))
  }
}

class CrunchUpdatesHandler[M](viewMode: () => ViewMode,
                              latestUpdate: ModelR[M, MillisSinceEpoch],
                              modelRW: ModelRW[M, (Pot[CrunchState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {
  val crunchUpdatesRequestFrequency: FiniteDuration = 2 seconds

  def latestUpdateMillis: MillisSinceEpoch = latestUpdate.value

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetCrunchState() =>
      val eventualAction = viewMode() match {
        case ViewLive() =>
          log.info(s"Calling getCrunchUpdates")
          implicit val pickler = generatePickler[ApiPaxTypeAndQueueCount]
          AjaxClient[Api].getCrunchUpdates(latestUpdateMillis).call()
            .map {
              case Some(cu) =>
                log.info(s"Got ${cu.flights.size} flights, ${cu.minutes.size} minutes")
                UpdateCrunchStateFromUpdatesAndContinuePolling(cu)
              case None =>
                GetCrunchUpdatesAfter(crunchUpdatesRequestFrequency)
            }
            .recoverWith {
              case f =>
                log.error(s"Update request failed. Re-requesting after ${PollDelay.recoveryDelay}")
                Future(GetCrunchUpdatesAfter(PollDelay.recoveryDelay))
            }

        case vm =>
          log.info(s"Requesting crunchState for point in time ${vm.time.prettyDateTime()}")

          implicit val pickler = generatePickler[ApiPaxTypeAndQueueCount]

          val call = vm match {
            case ViewPointInTime(time) =>
              log.info(s"Calling getCrunchStateForPointInTime ${time.prettyDateTime()}")
              AjaxClient[Api].getCrunchStateForPointInTime(time.millisSinceEpoch).call()
            case ViewDay(time) =>
              log.info(s"Calling getCrunchStateForDay ${time.prettyDateTime()}")
              AjaxClient[Api].getCrunchStateForDay(time.millisSinceEpoch).call()
            case _ => Future(None)
          }
          call.map {
            case Some(cs) =>
              log.info(s"Got ${cs.flights.size} flights, ${cs.crunchMinutes.size} minutes")
              UpdateCrunchStateFromCrunchState(cs)
            case None =>
              log.info(s"CrunchState not available")
              GetCrunchStateAfter(crunchUpdatesRequestFrequency)
          }.recoverWith {
            case f =>
              log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
              Future(GetCrunchStateAfter(PollDelay.recoveryDelay))
          }
      }
      val crunchState = modelRW.value._1

      crunchState match {
        case Ready(thing) =>
          updated((PendingStale(thing), latestUpdateMillis), Effect(Future(ShowLoader())) + Effect(eventualAction))
        case _ =>
          effectOnly(Effect(Future(ShowLoader())) + Effect(eventualAction))
      }

    case GetCrunchStateAfter(delay) =>
      log.info(s"Re-requesting CrunchState for ${viewMode().time.prettyDateTime()}")
      val getCrunchStateAfterDelay = Effect(Future(GetCrunchState())).after(delay)
      effectOnly(getCrunchStateAfterDelay)

    case UpdateCrunchStateFromCrunchState(crunchState: CrunchState) =>
      val oldCodes = value._1.map(cs => cs.flights.map(_.apiFlight.Origin)).getOrElse(Set())
      val newCodes = crunchState.flights.map(_.apiFlight.Origin)
      val unseenCodes = newCodes -- oldCodes
      val allEffects = if (unseenCodes.nonEmpty) {
        log.info(s"Requesting airport infos. Got unseen origin ports: ${unseenCodes.mkString(",")}")
        Effect(Future(GetAirportInfos(newCodes))) + Effect(Future(HideLoader()))
      } else {
        Effect(Future(HideLoader()))
      }
      updated((Ready(crunchState), 0L), allEffects)

    case UpdateCrunchStateFromUpdatesAndContinuePolling(crunchUpdates: CrunchUpdates) =>
      val getCrunchStateAfterDelay = Effect(Future(GetCrunchState())).after(crunchUpdatesRequestFrequency)
      val updateCrunchState = Effect(Future(UpdateCrunchStateFromUpdates(crunchUpdates)))
      val effects = getCrunchStateAfterDelay + updateCrunchState

      val oldCodes = value._1.map(cs => cs.flights.map(_.apiFlight.Origin)).getOrElse(Set())
      val newCodes = crunchUpdates.flights.map(_.apiFlight.Origin)
      val unseenCodes = newCodes -- oldCodes
      val allEffects = if (unseenCodes.nonEmpty) {
        log.info(s"Requesting airport infos. Got unseen origin ports: ${unseenCodes.mkString(",")}")
        effects + Effect(Future(GetAirportInfos(newCodes)))
      } else effects

      effectOnly(allEffects)

    case SetCrunchPending() =>
      log.info(s"Clearing out the crunch stuff")
      updated((Pending(), 0L))

    case GetCrunchUpdatesAfter(delay) =>
      val getCrunchStateAfterDelay = Effect(Future(GetCrunchState())).after(delay)
      effectOnly(getCrunchStateAfterDelay + Effect(Future(HideLoader())))

    case UpdateCrunchStateFromUpdates(crunchUpdates) =>
      log.info(s"Client got ${crunchUpdates.flights.size} flights & ${crunchUpdates.minutes.size} minutes from CrunchUpdates")

      val someStateExists = !value._1.isEmpty

      val newState = if (someStateExists) {
        val existingState = value._1.get
        updateStateFromUpdates(crunchUpdates, existingState)
      } else newStateFromUpdates(crunchUpdates)

      updated((PendingStale(newState), crunchUpdates.latest), Effect(Future(HideLoader())))
  }

  def newStateFromUpdates(crunchUpdates: CrunchUpdates): CrunchState = {
    CrunchState(crunchUpdates.flights, crunchUpdates.minutes, crunchUpdates.staff)
  }

  def updateStateFromUpdates(crunchUpdates: CrunchUpdates, existingState: CrunchState): CrunchState = {
    val lastMidnightMillis = SDate.midnightThisMorning().millisSinceEpoch
    val flights = updateAndTrimFlights(crunchUpdates, existingState, lastMidnightMillis)
    val minutes = updateAndTrimCrunch(crunchUpdates, existingState, lastMidnightMillis)
    val staff = updateAndTrimStaff(crunchUpdates, existingState, lastMidnightMillis)
    CrunchState(flights = flights, crunchMinutes = minutes, staffMinutes = staff)
  }

  def updateAndTrimCrunch(crunchUpdates: CrunchUpdates, existingState: CrunchState, keepFromMillis: MillisSinceEpoch): Set[CrunchApi.CrunchMinute] = {
    val relevantMinutes = existingState.crunchMinutes.filter(_.minute >= keepFromMillis)
    val existingMinutesByTqm = relevantMinutes.map(cm => ((cm.terminalName, cm.queueName, cm.minute), cm)).toMap
    val minutes = crunchUpdates.minutes.foldLeft(existingMinutesByTqm) {
      case (soFar, newCm) => soFar.updated((newCm.terminalName, newCm.queueName, newCm.minute), newCm)
    }.values.toSet
    minutes
  }

  def updateAndTrimStaff(crunchUpdates: CrunchUpdates, existingState: CrunchState, keepFromMillis: MillisSinceEpoch): Set[CrunchApi.StaffMinute] = {
    val relevantMinutes = existingState.staffMinutes.filter(_.minute >= keepFromMillis)
    val existingMinutesByTqm = relevantMinutes.map(cm => ((cm.terminalName, cm.minute), cm)).toMap
    val minutes = crunchUpdates.staff.foldLeft(existingMinutesByTqm) {
      case (soFar, newCm) => soFar.updated((newCm.terminalName, newCm.minute), newCm)
    }.values.toSet
    minutes
  }

  def updateAndTrimFlights(crunchUpdates: CrunchUpdates, existingState: CrunchState, keepFromMillis: MillisSinceEpoch): Set[ApiFlightWithSplits] = {
    val thirtyMinutesMillis = 30 * 60000
    val relevantFlights = existingState.flights.filter(keepFromMillis - thirtyMinutesMillis <= _.apiFlight.PcpTime)
    val flights = crunchUpdates.flights.foldLeft(relevantFlights) {
      case (soFar, newFlight) =>
        val withoutOldFlight = soFar.filterNot(_.apiFlight.uniqueId == newFlight.apiFlight.uniqueId)
        withoutOldFlight + newFlight
    }
    flights
  }
}

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportInfos(codes) =>
      log.info(s"Calling airportInfosByAirportCodes")
      val stringToObject: Map[String, Pot[AirportInfo]] = value ++ Map("BHX" -> mkPending, "EDI" -> mkPending)
      updated(stringToObject, Effect(AjaxClient[Api].airportInfosByAirportCodes(codes).call().map(UpdateAirportInfos)
        .recoverWith {
          case _ =>
            log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(GetAirportInfosAfter(codes, PollDelay.recoveryDelay))
        }
      ))
    case GetAirportInfosAfter(codes, delay) =>
      effectOnly(Effect(Future(GetAirportInfos(codes))).after(delay))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + (code -> Ready(airportInfo))
      updated(newValue)
  }
}

class ShiftsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetShifts(shifts: String) =>
      val scheduledRequest = Effect(Future(GetShifts())).after(15 seconds)

      updated(Ready(shifts), scheduledRequest)

    case AddShift(shift) =>
      updated(Ready(s"${value.getOrElse("")}\n${shift.toCsv}"))

    case GetShiftsAfter(delay) =>
      effectOnly(Effect(Future(GetShifts())).after(delay))

    case GetShifts() =>
      log.info(s"Calling getShifts")

      val apiCallEffect = Effect(AjaxClient[Api].getShifts(viewMode().millis).call()
        .map(SetShifts)
        .recoverWith {
          case _ =>
            log.error(s"Failed to get shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(GetShiftsAfter(PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
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

  def removeTerminalNameAndDate(rawFixedPoints: String): String = {
    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withTerminal = line.split(",").toList.map(_.trim)
      val withOutTerminal = withTerminal match {
        case fpName :: _ :: _ :: tail => fpName.toString :: tail
        case _ => Nil
      }
      withOutTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }

  def addTerminalNameAndDate(rawFixedPoints: String, terminalName: String): String = {
    val today: SDateLike = SDate.midnightThisMorning()
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

class FixedPointsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFixedPoints(fixedPoints: String, terminalName: Option[String]) =>
      if (terminalName.isDefined)
        updated(Ready(fixedPoints))
      else
        updated(Ready(fixedPoints))

    case SaveFixedPoints(fixedPoints: String, terminalName: TerminalName) =>
      log.info(s"Calling saveFixedPoints")

      val otherTerminalFixedPoints = FixedPoints.filterOtherTerminals(terminalName, value.getOrElse(""))
      val newRawFixedPoints = otherTerminalFixedPoints + "\n" + fixedPoints
      val futureResponse = AjaxClient[Api].saveFixedPoints(newRawFixedPoints).call()
        .map(_ => SetFixedPoints(newRawFixedPoints, Option(terminalName)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save FixedPoints. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(SaveFixedPointsAfter(fixedPoints, terminalName, PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case SaveFixedPointsAfter(fixedPoints, terminalName, delay) =>
      effectOnly(Effect(Future(SaveFixedPoints(fixedPoints, terminalName))).after(delay))

    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))

    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)
      log.info(s"Calling getFixedPoints")

      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints(viewMode().millis).call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}

class StaffMovementsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[Seq[StaffMovement]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovement(staffMovement) =>
      if (value.isReady) {
        val v: Seq[StaffMovement] = value.get
        val updatedValue: Seq[StaffMovement] = (v :+ staffMovement).sortBy(_.time)
        updated(Ready(updatedValue))
      } else noChange

    case RemoveStaffMovement(_, uUID) =>
      if (value.isReady) {
        val terminalAffectedOption = value.get.find(_.uUID == uUID).map(_.terminalName)
        if (terminalAffectedOption.isDefined) {
          val updatedValue = value.get.filter(_.uUID != uUID)
          updated(Ready(updatedValue), Effect(Future(SaveStaffMovements(terminalAffectedOption.get))))
        } else noChange
      } else noChange

    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      val scheduledRequest = Effect(Future(GetStaffMovements())).after(60 seconds)
      updated(Ready(staffMovements), scheduledRequest)

    case GetStaffMovements() =>
      log.info(s"Calling getStaffMovements")

      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements(viewMode().millis).call()
        .map(res => SetStaffMovements(res))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(GetStaffMovementsAfter(PollDelay.recoveryDelay))
        }
      )
      effectOnly(apiCallEffect)

    case GetStaffMovementsAfter(delay) =>
      effectOnly(Effect(Future(GetStaffMovements())).after(delay))

    case SaveStaffMovementsAfter(t, delay) =>
      effectOnly(Effect(Future(SaveStaffMovements(t))).after(delay))

    case SaveStaffMovements(t) =>
      if (value.isReady) {
        log.info(s"Calling saveStaffMovements")
        val responseFuture = AjaxClient[Api].saveStaffMovements(value.get).call()
          .map(_ => DoNothing())
          .recoverWith {
            case _ =>
              log.error(s"Failed to save Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
              Future(SaveStaffMovementsAfter(t, PollDelay.recoveryDelay))
          }
        effectOnly(Effect(responseFuture))
      } else noChange
  }
}

class ViewModeHandler[M](viewModeCrunchStateMP: ModelRW[M, (ViewMode, Pot[CrunchState], MillisSinceEpoch)], crunchStateMP: ModelR[M, Pot[CrunchState]]) extends LoggingActionHandler(viewModeCrunchStateMP) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, currentLatestUpdateMillis) = value

      val latestUpdateMillis = if (currentViewMode != ViewLive() && newViewMode == ViewLive()) 0L else currentLatestUpdateMillis

      log.info(s"VM: Set client newViewMode from $currentViewMode to $newViewMode. latestUpdateMillis: $latestUpdateMillis")
      (currentViewMode, newViewMode, crunchStateMP.value) match {
        case (_, _, cs@Pending(_)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case (ViewLive(), nvm, PendingStale(_, _)) if nvm != ViewLive() =>
          updated((newViewMode, Pending(), latestUpdateMillis))
        case (_, _, cs@PendingStale(_, _)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case _ =>
          updated((newViewMode, Pending(), latestUpdateMillis), Effect(Future(GetCrunchState())))
      }
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
    case ShowLoader() =>
      updated(LoadingState(isLoading = true))
    case HideLoader() =>
      updated(LoadingState(isLoading = false))
  }
}

class ShowActualDesksAndQueuesHandler[M](modelRW: ModelRW[M, Boolean]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateShowActualDesksAndQueues(state) =>
      updated(state)
  }
}

class ForecastHandler[M](modelRW: ModelRW[M, Pot[ForecastPeriodWithHeadlines]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetForecastWeek(startDay, terminalName) =>
      log.info(s"Calling forecastWeekSummary starting at ${startDay.toLocalDateTimeString()}")
      val apiCallEffect = Effect(AjaxClient[Api].forecastWeekSummary(startDay.millisSinceEpoch, terminalName).call()
        .map(res => SetForecastPeriod(res))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Forecast Period. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(GetForecastWeekAfter(startDay, terminalName, PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case GetForecastWeekAfter(startDay, terminalName, delay) =>
      effectOnly(Effect(Future(GetForecastWeek(startDay, terminalName))).after(delay))

    case SetForecastPeriod(Some(forecastPeriod)) =>
      log.info(s"Received forecast period.")
      updated(Ready(forecastPeriod))

    case SetForecastPeriod(None) =>
      log.info(s"No forecast available for requested dates")
      updated(Unavailable)
  }
}

class NoopHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case DoNothing() =>
      noChange
  }
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider(): MillisSinceEpoch = new Date().getTime.toLong

  override protected def initialModel = RootModel()

  def currentViewMode(): ViewMode = zoom(_.viewMode).value

  override val actionHandler: HandlerFunction = {
    val composedhandlers: HandlerFunction = composeHandlers(
      new CrunchUpdatesHandler(currentViewMode, zoom(_.latestUpdateMillis), zoomRW(m => (m.crunchStatePot, m.latestUpdateMillis))((m, v) => m.copy(crunchStatePot = v._1, latestUpdateMillis = v._2))),
      new ForecastHandler(zoomRW(_.forecastPeriodPot)((m, v) => m.copy(forecastPeriodPot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ShiftsHandler(currentViewMode, zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new FixedPointsHandler(currentViewMode, zoomRW(_.fixedPointsRaw)((m, v) => m.copy(fixedPointsRaw = v))),
      new StaffMovementsHandler(currentViewMode, zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new ViewModeHandler(zoomRW(m => (m.viewMode, m.crunchStatePot, m.latestUpdateMillis))((m, v) => m.copy(viewMode = v._1, crunchStatePot = v._2, latestUpdateMillis = v._3)), zoom(_.crunchStatePot)),
      new TimeRangeFilterHandler(zoomRW(_.timeRangeFilter)((m, v) => m.copy(timeRangeFilter = v))),
      new LoaderHandler(zoomRW(_.loadingState)((m, v) => m.copy(loadingState = v))),
      new ShowActualDesksAndQueuesHandler(zoomRW(_.showActualIfAvailable)((m, v) => m.copy(showActualIfAvailable = v))),
      new NoopHandler(zoomRW(identity)((m, v) => m))
    )

    val loggedhandlers: HandlerFunction = (model, update) => {
      composedhandlers(model, update)
    }

    loggedhandlers
  }

  def pointInTimeMillis: MillisSinceEpoch = zoom(_.viewMode).value.millis
}

object SPACircuit extends DrtCircuit
