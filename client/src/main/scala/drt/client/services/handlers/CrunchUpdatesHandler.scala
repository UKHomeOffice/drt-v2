package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi._
import drt.shared._

import scala.concurrent.Future
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class CrunchUpdatesHandler[M](airportConfigPot: () => Pot[AirportConfig],
                              viewMode: () => ViewMode,
                              latestUpdate: ModelR[M, MillisSinceEpoch],
                              modelRW: ModelRW[M, (Pot[CrunchState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {

  def latestUpdateMillis: MillisSinceEpoch = latestUpdate.value

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetCrunchState() =>
      val eventualCrunchUpdateAction = if (viewMode().millis >= midnightThisMorning.millisSinceEpoch)
        requestCrunchUpdates(viewMode().time)
      else
        requestHistoricCrunchState(viewMode())

      val crunchState: Pot[CrunchState] = modelRW.value._1

      val effects = Effect(Future(ShowLoader())) + Effect(eventualCrunchUpdateAction)

      crunchState match {
        case Ready(cs) =>
          updated((PendingStale(cs), latestUpdateMillis), effects)
        case _ =>
          effectOnly(effects)
      }

    case NoCrunchUpdates =>
      val thereIsNoData = modelRW.value._1.headOption.isEmpty

      if (thereIsNoData)
        updated((Empty, SDate.now().millisSinceEpoch), Effect(Future(HideLoader())))
      else effectOnly(Effect(Future(HideLoader())))

    case UpdateCrunchStateFromCrunchState(crunchState: CrunchState) =>
      val oldCodes =
        value._1.map(cs => cs.flights.map(_.apiFlight.Origin)).getOrElse(Set())
      val newCodes = crunchState.flights.map(_.apiFlight.Origin)
      val unseenCodes = newCodes -- oldCodes
      val allEffects = if (unseenCodes.nonEmpty) {
        log.info(s"Requesting airport infos. Got unseen origin ports: ${unseenCodes.mkString(",")}")
        Effect(Future(GetAirportInfos(newCodes))) + Effect(Future(HideLoader()))
      } else {
        Effect(Future(HideLoader()))
      }
      log.info(s"set crunchstate ready")
      updated((Ready(crunchState), 0L), allEffects)

    case UpdateCrunchStateFromUpdates(crunchUpdates) =>
      log.info(s"Client got ${crunchUpdates.flights.size} flights & ${crunchUpdates.minutes.size} minutes from CrunchUpdates")

      val oldCodes = value._1.map(cs => cs.flights.map(_.apiFlight.Origin)).getOrElse(Set())
      val newCodes = crunchUpdates.flights.map(_.apiFlight.Origin)
      val unseenCodes = newCodes -- oldCodes
      val allEffects = if (unseenCodes.nonEmpty) {
        log.info(s"Requesting airport infos. Got unseen origin ports: ${unseenCodes.mkString(",")}")
        Effect(Future(GetAirportInfos(newCodes))) + Effect(Future(HideLoader()))
      } else Effect(Future(HideLoader()))

      val someStateExists = !value._1.isEmpty

      val newState = if (someStateExists) {
        val existingState = value._1.get
        updateStateFromUpdates(crunchUpdates, existingState)
      } else newStateFromUpdates(crunchUpdates)

      log.info(s"set crunchstate pendingstale")
      updated((PendingStale(newState), crunchUpdates.latest), allEffects)

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
      case _ => Future(Right(None))
    }

    processFutureCrunch(futureCrunchState)
  }

  def processFutureCrunch[U, E](call: Future[Either[E, Option[U]]]): Future[Action] = {
    call.map {
      case Right(Some(cs: CrunchState)) =>
        log.info(s"Got CrunchState with ${cs.flights.size} flights, ${cs.crunchMinutes.size} minutes")
        if (cs.isEmpty) NoCrunchUpdates else UpdateCrunchStateFromCrunchState(cs)
      case Right(Some(cu: CrunchUpdates)) =>
        log.info(s"Got CrunchUpdates with ${cu.flights.size} flights, ${cu.minutes.size} minutes")
        UpdateCrunchStateFromUpdates(cu)
      case Left(e: CrunchStateError) =>
        log.error(s"Failed to GetCrunchState ${e.message}. Polling will continue.")
        NoAction
      case _ =>
        log.info(s"No CrunchUpdates ${SDate.now().getSeconds()}")
        NoCrunchUpdates
    }
      .recoverWith {
        case _ =>
          log.error(s"Failed to GetCrunchState. Polling will continue.")
          Future(NoAction)
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

  def dayStart(pointInTime: SDateLike): SDateLike = SDate.midnightOf(pointInTime)

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
    val relevantFlights = existingState.flights.filter(_.apiFlight.PcpTime.isDefined).filter(keepFromMillis - thirtyMinutesMillis <= _.apiFlight.PcpTime.getOrElse(0L))
    val flights = crunchUpdates.flights.foldLeft(relevantFlights) {
      case (soFar, newFlight) =>
        val withoutOldFlight = soFar.filterNot(_.apiFlight.uniqueId == newFlight.apiFlight.uniqueId)
        withoutOldFlight + newFlight
    }
    flights
  }
}
