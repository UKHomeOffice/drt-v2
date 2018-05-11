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
import org.scalajs.dom
import org.scalajs.dom.ext.AjaxException

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}

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

case class ClientServerVersions(client: String, server: String)

case class RootModel(
                      applicationVersion: Pot[ClientServerVersions] = Empty,
                      latestUpdateMillis: MillisSinceEpoch = 0L,
                      crunchStatePot: Pot[CrunchState] = Empty,
                      forecastPeriodPot: Pot[ForecastPeriodWithHeadlines] = Empty,
                      airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                      airportConfig: Pot[AirportConfig] = Empty,
                      shiftsRaw: Pot[String] = Empty,
                      monthOfShifts: Pot[MonthOfRawShifts] = Empty,
                      fixedPointsRaw: Pot[String] = Empty,
                      staffMovements: Pot[Seq[StaffMovement]] = Empty,
                      viewMode: ViewMode = ViewLive(),
                      loadingState: LoadingState = LoadingState(),
                      showActualIfAvailable: Boolean = true,
                      userRoles: Pot[List[String]] = Empty,
                      minuteTicker: Int = 0
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
  val loginCheckDelay: FiniteDuration = 30 seconds
  val minuteUpdateDelay: FiniteDuration = 10 seconds
}

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case _: GetAirportConfig =>
      log.info(s"Calling airportConfiguration")
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetAirportConfig(), PollDelay.recoveryDelay))
      }))
    case UpdateAirportConfig(configHolder) =>
      updated(Ready(configHolder))
  }
}

class CrunchUpdatesHandler[M](airportConfigPot: () => Pot[AirportConfig],
                              viewMode: () => ViewMode,
                              latestUpdate: ModelR[M, MillisSinceEpoch],
                              modelRW: ModelRW[M, (Pot[CrunchState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {
  val crunchUpdatesRequestFrequency: FiniteDuration = 2 seconds

  def latestUpdateMillis: MillisSinceEpoch = latestUpdate.value

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetCrunchState() =>
      val eventualAction = viewMode() match {
        case vm@ViewLive() => requestCrunchUpdates(vm.time)

        case ViewDay(time) if time.millisSinceEpoch >= midnightThisMorning.millisSinceEpoch => requestCrunchUpdates(time)

        case vm => requestHistoricCrunchState(vm)
      }
      val crunchState = modelRW.value._1

      val effects = if (isPollingForUpdates)
        Effect(eventualAction)
      else
        Effect(Future(ShowLoader())) + Effect(eventualAction)

      crunchState match {
        case Ready(thing) =>
          updated((PendingStale(thing), latestUpdateMillis), effects)
        case _ =>
          effectOnly(effects)
      }

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
      log.info(s"UpdateCrunchStateFromUpdatesAndContinuePolling ")
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

    case UpdateCrunchStateFromUpdates(crunchUpdates) =>
      log.info(s"Client got ${crunchUpdates.flights.size} flights & ${crunchUpdates.minutes.size} minutes from CrunchUpdates")

      val someStateExists = !value._1.isEmpty

      val newState = if (someStateExists) {
        val existingState = value._1.get
        updateStateFromUpdates(crunchUpdates, existingState)
      } else newStateFromUpdates(crunchUpdates)

      updated((PendingStale(newState), crunchUpdates.latest), Effect(Future(HideLoader())))
  }

  def requestHistoricCrunchState(viewMode: ViewMode): Future[Action] = {
    log.info(s"Requesting CrunchState for point in time ${viewMode.time.prettyDateTime()}")

    implicit val pickler = generatePickler[ApiPaxTypeAndQueueCount]

    val futureCrunchState = viewMode match {
      case ViewPointInTime(time) =>
        log.info(s"Calling getCrunchStateForPointInTime ${time.prettyDateTime()}")
        AjaxClient[Api].getCrunchStateForPointInTime(time.millisSinceEpoch).call()
      case ViewDay(time) =>
        log.info(s"Calling getCrunchStateForDay ${time.prettyDateTime()}")
        AjaxClient[Api].getCrunchStateForDay(time.millisSinceEpoch).call()
      case _ => Future(None)
    }

    processFutureCrunch(futureCrunchState)
  }

  def processFutureCrunch[U](call: Future[Option[U]]): Future[Action] = {
    call.map {
      case Some(cs: CrunchState) =>
        log.info(s"Got CrunchState with ${cs.flights.size} flights, ${cs.crunchMinutes.size} minutes")
        UpdateCrunchStateFromCrunchState(cs)
      case Some(cu: CrunchUpdates) =>
        log.info(s"Got CrunchUpdates with ${cu.flights.size} flights, ${cu.minutes.size} minutes")
        UpdateCrunchStateFromUpdatesAndContinuePolling(cu)
      case None =>
        RetryActionAfter(GetCrunchState(), crunchUpdatesRequestFrequency)
    }.recoverWith {
      case _ =>
        log.error(s"Failed to GetCrunchState. Re-requesting after ${PollDelay.recoveryDelay}")
        Future(RetryActionAfter(GetCrunchState(), PollDelay.recoveryDelay))
    }
  }

  def requestCrunchUpdates(pointInTime: SDateLike): Future[Action] = {
    implicit val pickler = generatePickler[ApiPaxTypeAndQueueCount]

    val startOfDay = dayStart(pointInTime)
    val endOfDay = dayEnd(pointInTime)

    log.info(s"Calling getCrunchUpdates for ${startOfDay.toISOString()} to ${endOfDay.toISOString()}")

    val futureCrunchUpdates = AjaxClient[Api].getCrunchUpdates(latestUpdateMillis, startOfDay.millisSinceEpoch, endOfDay.millisSinceEpoch).call()

    processFutureCrunch(futureCrunchUpdates)
  }

  def isPollingForUpdates: Boolean = {
    val liveModeUpdating = viewMode() == ViewLive() && latestUpdateMillis != 0L
    val dayModeUpdating = viewMode() match {
      case ViewDay(time) =>
        val notPastDay = time.millisSinceEpoch > midnightThisMorning.millisSinceEpoch
        notPastDay && latestUpdateMillis != 0L
      case _ => false
    }

    liveModeUpdating || dayModeUpdating
  }

  def newStateFromUpdates(crunchUpdates: CrunchUpdates): CrunchState = {
    CrunchState(crunchUpdates.flights, crunchUpdates.minutes, crunchUpdates.staff)
  }

  def updateStateFromUpdates(crunchUpdates: CrunchUpdates, existingState: CrunchState): CrunchState = {
    val lastMidnightMillis = midnightThisMorning.millisSinceEpoch
    val flights = updateAndTrimFlights(crunchUpdates, existingState, lastMidnightMillis)
    val minutes = updateAndTrimCrunch(crunchUpdates, existingState, lastMidnightMillis)
    val staff = updateAndTrimStaff(crunchUpdates, existingState, lastMidnightMillis)
    CrunchState(flights = flights, crunchMinutes = minutes, staffMinutes = staff)
  }

  def midnightThisMorning: SDateLike = dayStart(SDate.now())

  def dayStart(pointInTime: SDateLike): SDateLike = SDate.dayStart(pointInTime)

  def dayEnd(pointInTime: SDateLike): SDateLike = dayStart(pointInTime)
    .addHours(airportConfigPot().map(_.dayLengthHours).getOrElse(24))

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
            Future(RetryActionAfter(GetAirportInfos(codes), PollDelay.recoveryDelay))
        }
      ))
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

    case GetShifts() =>
      log.info(s"Calling getShifts")

      val apiCallEffect = Effect(AjaxClient[Api].getShifts(viewMode().millis).call()
        .map(SetShifts)
        .recoverWith {
          case _ =>
            log.error(s"Failed to get shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetShifts(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}

class ShiftsForMonthHandler[M](modelRW: ModelRW[M, Pot[MonthOfRawShifts]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SaveMonthTimeSlotsToShifts(staffTimeSlots) =>

      log.info(s"Saving staff time slots as Shifts")
      val action: Future[Action] = AjaxClient[Api].saveStaffTimeSlotsForMonth(staffTimeSlots).call().map(_ => NoAction)
        .recoverWith {
          case error =>
            log.error(s"Failed to save staff month timeslots: $error, retrying after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveMonthTimeSlotsToShifts(staffTimeSlots), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(action))

    case GetShiftsForMonth(month, terminalName) =>
      log.info(s"Calling getShifts for Month")

      val apiCallEffect = Effect(AjaxClient[Api].getShiftsForMonth(month.millisSinceEpoch, terminalName).call()
        .map(s => SetShiftsForMonth(MonthOfRawShifts(month.millisSinceEpoch, s)))
        .recoverWith {
          case t =>
            log.error(s"Failed to get shifts. Re-requesting after ${PollDelay.recoveryDelay}: $t")
            Future(RetryActionAfter(GetShiftsForMonth(month, terminalName), PollDelay.recoveryDelay))
        })
      updated(Pending(), apiCallEffect)
    case SetShiftsForMonth(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
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
            Future(RetryActionAfter(SaveFixedPoints(fixedPoints, terminalName), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))

    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)
      log.info(s"Calling getFixedPoints")

      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints(viewMode().millis).call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}

class ApplicationVersionHandler[M](modelRW: ModelRW[M, Pot[ClientServerVersions]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetApplicationVersion =>
      log.info(s"Calling getApplicationVersion")

      val nextCallEffect = Effect(Future(GetApplicationVersion)).after(PollDelay.recoveryDelay)

      val effect = Effect(AjaxClient[Api].getApplicationVersion().call().map(serverVersionContent => {
        Try(Integer.parseInt(serverVersionContent)) match {
          case Success(serverVersionInt) =>
            val serverVersion = serverVersionInt.toString
            value match {
              case Ready(ClientServerVersions(clientVersion, _)) if serverVersion != clientVersion =>
                UpdateServerApplicationVersion(serverVersion)
              case Ready(_) =>
                log.info(s"server application version unchanged ($serverVersionInt)")
                NoAction
              case Empty =>
                SetApplicationVersion(serverVersion)
              case u =>
                log.info(s"Got a $u")
                NoAction
            }
          case Failure(t) =>
            log.info(s"Failed to parse application version number from response: $t")
            NoAction
        }
      }))

      effectOnly(nextCallEffect + effect)

    case SetApplicationVersion(newVersion) =>
      log.info(s"Setting application version to $newVersion")
      updated(Ready(ClientServerVersions(newVersion, newVersion)))

    case UpdateServerApplicationVersion(newServerVersion) =>
      log.info(s"Updating server application version to $newServerVersion")
      val newClientServerVersions = value.map(_.copy(server = newServerVersion))
      updated(newClientServerVersions)
  }
}

class StaffMovementsHandler[M](modelRW: ModelRW[M, (Pot[Seq[StaffMovement]], ViewMode)]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case (AddStaffMovement(staffMovement)) =>
      value match {
        case (Ready(sms), vm) =>

          val updatedValue: Seq[StaffMovement] = (sms :+ staffMovement).sortBy(_.time)
          updated((Ready(updatedValue), vm))
        case _ => noChange
      }


    case RemoveStaffMovement(_, uUID) =>
      value match {
        case (Ready(sms), vm) if sms.exists(_.uUID == uUID) =>

          sms.find(_.uUID == uUID).map(_.terminalName).map(terminal => {
            val updatedValue = sms.filter(_.uUID != uUID)
            updated((Ready(updatedValue), vm), Effect(Future(SaveStaffMovements(terminal))))
          })
            .getOrElse(noChange)

        case _ => noChange
      }


    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      val scheduledRequest = Effect(Future(GetStaffMovements())).after(60 seconds)
      updated((Ready(staffMovements), value._2), scheduledRequest)

    case GetStaffMovements() =>
      val (_, viewMode) = value
      log.info(s"Calling getStaffMovements with ${SDate(viewMode.millis).toISOString()}")


      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements(viewMode.millis).call()
        .map(res => {
          log.info(s"Got StaffMovements from the server")
          SetStaffMovements(res)
        })
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetStaffMovements(), PollDelay.recoveryDelay))
        }
      )
      effectOnly(apiCallEffect)

    case SaveStaffMovements(t) =>
      value match {
        case (Ready(sms), vm) =>
          log.info(s"Calling saveStaffMovements")
          val responseFuture = AjaxClient[Api].saveStaffMovements(sms).call()
            .map(_ => DoNothing())
            .recoverWith {
              case _ =>
                log.error(s"Failed to save Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
                Future(RetryActionAfter(SaveStaffMovements(t), PollDelay.recoveryDelay))
            }
          effectOnly(Effect(responseFuture))
        case _ =>
          noChange
      }
  }
}

class ViewModeHandler[M](viewModeCrunchStateMP: ModelRW[M, (ViewMode, Pot[CrunchState], MillisSinceEpoch)], crunchStateMP: ModelR[M, Pot[CrunchState]]) extends LoggingActionHandler(viewModeCrunchStateMP) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, currentLatestUpdateMillis) = value

      val latestUpdateMillis = (newViewMode, currentViewMode) match {
        case (newVm, oldVm) if newVm != oldVm => 0L
        case (ViewDay(newTime), ViewDay(oldTime)) if newTime != oldTime => 0L
        case _ => currentLatestUpdateMillis
      }
//      val latestUpdateMillis = if (newViewMode == currentViewMode) currentLatestUpdateMillis else 0L

      log.info(s"VM: Set client newViewMode from $currentViewMode to $newViewMode. latestUpdateMillis: $latestUpdateMillis")
      (currentViewMode, newViewMode, crunchStateMP.value) match {
        case (_, _, cs@Pending(_)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case (ViewLive(), nvm, PendingStale(_, _)) if nvm != ViewLive() =>
          updated((newViewMode, Pending(), latestUpdateMillis))
        case (_, _, cs@PendingStale(_, _)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case _ =>
          val effects = Effect(Future(GetCrunchState())) + Effect(Future(GetStaffMovements())) + Effect(Future(GetShifts()))
          updated((newViewMode, Pending(), latestUpdateMillis), effects)
      }
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
            Future(RetryActionAfter(GetForecastWeek(startDay, terminalName), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case SetForecastPeriod(Some(forecastPeriod)) =>
      log.info(s"Received forecast period.")
      updated(Ready(forecastPeriod))

    case SetForecastPeriod(None) =>
      log.info(s"No forecast available for requested dates")
      updated(Unavailable)
  }
}

class UserRolesHandler[M](modelRW: ModelRW[M, Pot[List[String]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetUserRoles =>
      effectOnly(Effect(AjaxClient[Api].getUserRoles().call().map(SetUserRoles).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetUserRoles, PollDelay.recoveryDelay))
      }))

    case SetUserRoles(roles) =>
      log.info(s"Roles: $roles")
      updated(Ready(roles))
  }
}

class MinuteTickerHandler[M](modelRW: ModelRW[M, Int]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateMinuteTicker =>
      val currentMinutes = SDate.now().getMinutes()

      val pollEffect = Effect(Future(RetryActionAfter(UpdateMinuteTicker, PollDelay.minuteUpdateDelay)))
      if (currentMinutes != value)
        updated(currentMinutes, pollEffect)
      else
        effectOnly(pollEffect)
  }
}

class NoopHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case DoNothing() =>
      noChange
  }
}

class RetryHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RetryActionAfter(actionToRetry, delay) =>
      effectOnly(Effect(Future(actionToRetry)).after(delay))
  }
}

class LoggedInStatusHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetLoggedInStatus =>
      effectOnly(
        Effect(
          AjaxClient[Api]
            .isLoggedIn()
            .call()
            .map(_ => RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay))
            .recoverWith {
              case f: AjaxException if f.xhr.status == 405 =>
                log.error(s"User is logged out, triggering page reload.")
                Future(TriggerReload)
              case f =>
                log.error(s"Error when checking for user login status, retrying after ${PollDelay.loginCheckDelay}")
                Future(RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay))
            }
        ))

    case TriggerReload =>
      log.info(s"LoginStatus: triggering reload")
      dom.window.location.reload(true)
      noChange
  }
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider(): MillisSinceEpoch = new Date().getTime.toLong

  override protected def initialModel = RootModel()

  def currentViewMode(): ViewMode = zoom(_.viewMode).value

  def airportConfigPot(): Pot[AirportConfig] = zoomTo(_.airportConfig).value

  def pointInTimeMillis: MillisSinceEpoch = zoom(_.viewMode).value.millis

  override val actionHandler: HandlerFunction = {
    val composedhandlers: HandlerFunction = composeHandlers(
      new CrunchUpdatesHandler(airportConfigPot, currentViewMode, zoom(_.latestUpdateMillis), zoomRW(m => (m.crunchStatePot, m.latestUpdateMillis))((m, v) => m.copy(crunchStatePot = v._1, latestUpdateMillis = v._2))),
      new ForecastHandler(zoomRW(_.forecastPeriodPot)((m, v) => m.copy(forecastPeriodPot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ApplicationVersionHandler(zoomRW(_.applicationVersion)((m, v) => m.copy(applicationVersion = v))),
      new ShiftsHandler(currentViewMode, zoomRW(_.shiftsRaw)((m, v) => m.copy(shiftsRaw = v))),
      new ShiftsForMonthHandler(zoomRW(_.monthOfShifts)((m, v) => m.copy(monthOfShifts = v))),
      new FixedPointsHandler(currentViewMode, zoomRW(_.fixedPointsRaw)((m, v) => m.copy(fixedPointsRaw = v))),
      new StaffMovementsHandler(zoomRW(m => (m.staffMovements, m.viewMode))((m, v) => m.copy(staffMovements = v._1))),
      new ViewModeHandler(zoomRW(m => (m.viewMode, m.crunchStatePot, m.latestUpdateMillis))((m, v) => m.copy(viewMode = v._1, crunchStatePot = v._2, latestUpdateMillis = v._3)), zoom(_.crunchStatePot)),
      new LoaderHandler(zoomRW(_.loadingState)((m, v) => m.copy(loadingState = v))),
      new ShowActualDesksAndQueuesHandler(zoomRW(_.showActualIfAvailable)((m, v) => m.copy(showActualIfAvailable = v))),
      new RetryHandler(zoomRW(identity)((m, v) => m)),
      new LoggedInStatusHandler(zoomRW(identity)((m, v) => m)),
      new NoopHandler(zoomRW(identity)((m, v) => m)),
      new UserRolesHandler(zoomRW(_.userRoles)((m, v) => m.copy(userRoles = v))),
      new MinuteTickerHandler(zoomRW(_.minuteTicker)((m, v) => m.copy(minuteTicker = v)))
    )

    composedhandlers
  }
}

object SPACircuit extends DrtCircuit
