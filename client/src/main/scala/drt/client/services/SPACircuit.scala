package drt.client.services

import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers._
import drt.shared.CrunchApi._
import drt.shared.KeyCloakApi.KeyCloakUser
import drt.shared._

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.js.Date

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
                      minuteTicker: Int = 0,
                      keyCloakUsers: Pot[List[KeyCloakUser]] = Empty,
                      feedStatuses: Pot[Seq[FeedStatuses]] = Empty
                    )

object PollDelay {
  val recoveryDelay: FiniteDuration = 10 seconds
  val loginCheckDelay: FiniteDuration = 30 seconds
  val minuteUpdateDelay: FiniteDuration = 10 seconds
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
      new UsersHandler(zoomRW(_.keyCloakUsers)((m, v) => m.copy(keyCloakUsers = v))),
      new MinuteTickerHandler(zoomRW(_.minuteTicker)((m, v) => m.copy(minuteTicker = v))),
      new FeedsStatusHandler(zoomRW(_.feedStatuses)((m, v) => m.copy(feedStatuses = v)))
    )

    composedhandlers
  }
}

object SPACircuit extends DrtCircuit
