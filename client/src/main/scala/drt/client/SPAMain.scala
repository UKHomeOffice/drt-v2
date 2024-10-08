package drt.client

import diode.Action
import drt.client.actions.Actions._
import drt.client.components.TerminalDesksAndQueues.{ChartsView, Deployments, DeskType, DisplayType, Ideal, TableView}
import drt.client.components.styles._
import drt.client.components.{FeedsStatusPage, ForecastUploadComponent, GlobalStyles, Layout, PortConfigPage, PortDashboardPage, TerminalComponent, TrainingHubComponent, UserDashboardPage}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.services.handlers.GetFeedSourceStatuses
import drt.client.spa.TerminalPageModes.{Current, Staffing}
import drt.client.spa.{TerminalPageMode, TerminalPageModes}
import drt.shared.CrunchApi.{CrunchMinutes, PassengersMinutes, StaffMinutes}
import drt.shared.WsMessage
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom
import org.scalajs.dom.{WebSocket, console, window}
import scalacss.ProdDefaults._
import uk.gov.homeoffice.drt.Urls
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FlightsWithSplitsDiff, SplitsForArrivals}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.AirportConfigs
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default

import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scala.util.Try

object SPAMain {

  sealed trait Loc {
    val portCodeStr = dom.document.getElementById("port-code").getAttribute("value")
    val portConfig = AirportConfigs.confByPort(PortCode(portCodeStr))
    val url = window.location.href

    def terminalPart(maybeTerminal: Option[Terminal]): String = {
      val terminalShortName = maybeTerminal.map { t =>
        val terminalStr = t.toString
        if (terminalStr.take(1) == "T") terminalStr.drop(1) else terminalStr
      }
      terminalShortName.map(t => s", Terminal $t").getOrElse("")
    }

    def title(pageName: String, maybeTerminal: Option[Terminal]) =
      s"$pageName at ${portConfig.portCode.iata} (${portConfig.portName})${terminalPart(maybeTerminal)} - DRT"

    def title(maybeTerminal: Option[Terminal]): String
  }

  sealed trait UrlParameter {
    val name: String
    val value: Option[String]
  }

  trait UrlDateLikeParameter {
    val paramName: String

    def apply(paramValue: Option[String]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = paramValue
    }
  }

  object UrlDateParameter extends UrlDateLikeParameter {
    override val paramName = "date"
  }

  object UrlTimeMachineDateParameter extends UrlDateLikeParameter {
    override val paramName = "tm-date"
  }

  object UrlTimeRangeStart extends UrlDateLikeParameter {
    override val paramName = "timeRangeStart"
  }

  object UrlTimeRangeEnd extends UrlDateLikeParameter {
    override val paramName = "timeRangeEnd"
  }

  object UrlViewType {
    val paramName = "viewType"

    def apply(viewType: Option[DeskType]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = viewType.map(_.queryParamsValue)
    }
  }

  object UrlDisplayType {
    val paramName = "displayType"

    def apply(viewType: Option[DisplayType]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = viewType.map(_.queryParamsValue)
    }
  }

  object TerminalPageTabLoc {
    def apply(terminalName: String,
              mode: TerminalPageMode,
              subMode: String,
              queryParams: Map[String, String]): TerminalPageTabLoc =
      TerminalPageTabLoc(terminalName, mode.toString, subMode, queryParams)
  }

  case class TerminalPageTabLoc(terminalName: String,
                                modeStr: String = "current",
                                subMode: String = "arrivals",
                                queryParams: Map[String, String] = Map.empty[String, String]
                               ) extends Loc {
    val pageName = subMode match {
      case "arrivals" => "Arrivals"
      case "desksAndQueues" => "Desks and queues"
      case "staffing" => "Monthly staffing"
      case "simulations" => "Simulate day"
      case other => ""
    }
    override def title(maybeTerminal: Option[Terminal]): String = title(pageName, maybeTerminal)

    val terminal: Terminal = Terminal(terminalName)
    val maybeViewDate: Option[LocalDate] = queryParams.get(UrlDateParameter.paramName)
      .filter(_.matches(".+"))
      .flatMap(dateStr => Try {
        val parts = dateStr.split("-")
        LocalDate(parts(0).toInt, parts(1).toInt, parts(2).toInt)
      }.toOption)
    val maybeTimeMachineDate: Option[SDateLike] = queryParams.get(UrlTimeMachineDateParameter.paramName)
      .filter(_.matches(".+"))
      .flatMap(dateStr => Try(parseDateString(dateStr)).toOption)
    val timeRangeStartString: Option[String] = queryParams.get(UrlTimeRangeStart.paramName).filter(_.matches("[0-9]+"))
    val timeRangeEndString: Option[String] = queryParams.get(UrlTimeRangeEnd.paramName).filter(_.matches("[0-9]+"))
    val deskType: DeskType = queryParams.get(UrlViewType.paramName).map(vt => if (Ideal.queryParamsValue == vt) Ideal else Deployments).getOrElse(Deployments)
    val displayAs: DisplayType = queryParams.get(UrlDisplayType.paramName).map(vt => if (TableView.queryParamsValue == vt) TableView else ChartsView).getOrElse(TableView)
    val mode: TerminalPageMode = TerminalPageModes.fromString(modeStr)

    def viewMode: ViewMode = {
      (mode, maybeViewDate) match {
        case (Current, Some(viewDate)) =>
          ViewDay(viewDate, maybeTimeMachineDate)
        case (Current, None) if maybeTimeMachineDate.isDefined =>
          ViewDay(SDate.now().toLocalDate, maybeTimeMachineDate)
        case _ =>
          ViewLive
      }
    }

    def withUrlParameters(urlParameters: UrlParameter*): TerminalPageTabLoc = {
      val updatedParams = urlParameters.foldLeft(queryParams) {
        case (paramsSoFar, newParam) => newParam.value match {
          case Some(newValue) => paramsSoFar.updated(newParam.name, newValue)
          case _ => paramsSoFar - newParam.name
        }
      }
      copy(queryParams = updatedParams)
    }

    def parseDateString(s: String): SDateLike = SDate(s.replace("%20", " ").split(" ").mkString("T"))

    def timeRangeStart: Option[Int] = timeRangeStartString.map(_.toInt)

    def timeRangeEnd: Option[Int] = timeRangeEndString.map(_.toInt)

    def dateFromUrlOrNow: SDateLike = maybeViewDate.map(ld => SDate(ld)).getOrElse(SDate.now())

    def updateRequired(p: TerminalPageTabLoc): Boolean =
      (terminal != p.terminal) || (maybeViewDate != p.maybeViewDate) || (mode != p.mode) || (maybeTimeMachineDate != p.maybeTimeMachineDate)

    def loadAction: Action = mode match {
      case Staffing =>
        GetShiftsForMonth(dateFromUrlOrNow)
      case _ =>
        SetViewMode(viewMode)
    }

    def update(mode: TerminalPageMode, subMode: String, queryParams: Map[String, String] = Map[String, String]()): TerminalPageTabLoc =
      copy(modeStr = mode.asString, subMode = subMode, queryParams = queryParams)
  }

  def serverLogEndpoint: String = absoluteUrl("logging")

  case class PortDashboardLoc(period: Option[Int]) extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Dashboard", maybeTerminal)
  }

  case object FeedsStatusLoc extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Feeds status", maybeTerminal)
  }

  case object UserDashboardLoc extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Dashboard", maybeTerminal)
  }

  case class TrainingHubLoc(modeStr: String = "dropInBooking") extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Training hub", maybeTerminal)
  }

  case object PortConfigLoc extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Port config", maybeTerminal)
  }

  case object ForecastFileUploadLoc extends Loc {
    override def title(maybeTerminal: Option[Terminal]): String = title("Forecast upload", maybeTerminal)
  }

  private val initialRequestsActions = Seq(
    GetApplicationVersion,
    GetContactDetails,
    GetLoggedInUser,
    GetUserHasPortAccess,
    GetLoggedInStatus,
    GetAirportConfig,
    GetPaxFeedSourceOrder,
    UpdateMinuteTicker,
    GetFeedSourceStatuses(),
    GetAlerts(0L),
    GetRedListUpdates,
    GetPortEgateBanksUpdates,
    GetOohStatus,
    GetFeatureFlags,
    GetGateStandWalktime,
    GetManifestSummariesForDate(SDate.now().toUtcDate),
    GetManifestSummariesForDate(SDate.now().addDays(-1).toUtcDate),
    GetSlaConfigs,
  )

  private def getWebsocketUri(): String = {
    val wsProtocol = if (dom.document.location.protocol == "https:") "wss" else "ws"

    s"$wsProtocol://${dom.document.location.host}/ws"
  }

  val websocket = new WebSocket(getWebsocketUri())
  websocket.onopen = { event =>
    log.info(s"Websocket opened: $event")
  }
  websocket.onerror = { event =>
    log.error(s"Websocket error: $event")
  }
  websocket.onclose = { event =>
    log.info(s"Websocket closed: $event")
  }
  websocket.onmessage = { event =>
    log.info(s"Websocket message: ${event.data.toString}")
    val msg = default.read[WsMessage](event.data.toString)
    log.info(s"WsMessage: $msg")
    val action = msg.kind match {
      case "FlightsWithSplitsDiff" => ApplyFlightUpdates(default.read[FlightsWithSplitsDiff](msg.payload))
      case "ArrivalsDiff" => ApplyFlightUpdates(default.read[ArrivalsDiff](msg.payload))
      case "SplitsForArrivals" => ApplyFlightUpdates(default.read[SplitsForArrivals](msg.payload))
      case "CrunchMinutes" => ApplyMinuteUpdates(default.read[CrunchMinutes](msg.payload))
      case "StaffMinutes" => ApplyMinuteUpdates(default.read[StaffMinutes](msg.payload))
      case "PassengersMinutes" => ApplyMinuteUpdates(default.read[PassengersMinutes](msg.payload))
    }
    SPACircuit.dispatch(action)
  }

  def sendInitialRequests(): Unit = initialRequestsActions.foreach(SPACircuit.dispatch(_))

  val routerConfig: RouterConfig[Loc] = RouterConfigDsl[Loc]
    .buildConfig { dsl: RouterConfigDsl[Loc, Unit] =>
      import dsl._

      val rule = homeRoute(dsl) |
        dashboardRoute(dsl) |
        terminalRoute(dsl) |
        statusRoute(dsl) |
        trainingHubRoute(dsl) |
        portConfigRoute(dsl) |
        forecastFileUploadRoute(dsl)

      rule
        .notFound(redirectToPage(PortDashboardLoc(None))(SetRouteVia.HistoryReplace))
        .setTitle { loc: Loc =>
          val regex = """.+terminal/([A-Z0-9]+)/.+""".r
          val url = window.location.href
          val maybeTerminal = url match {
            case regex(t) => Some(Terminal(t))
            case _ => None
          }
          loc.title(maybeTerminal)
        }
    }
    .renderWith(Layout(_, _))
    .onPostRender((maybePrevLoc, currentLoc) => {
      Callback(
        (maybePrevLoc, currentLoc) match {
          case (Some(p: TerminalPageTabLoc), c: TerminalPageTabLoc) =>
            if (c.updateRequired(p)) SPACircuit.dispatch(c.loadAction)
          case (None, c: TerminalPageTabLoc) =>
            SPACircuit.dispatch(c.loadAction)
          case (_, UserDashboardLoc) =>
            SPACircuit.dispatch(GetUserDashboardState)
          case _ =>
        }
      )
    })

  def homeRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute(root, UserDashboardLoc) ~> renderR { (router: RouterCtl[Loc]) =>
      UserDashboardPage(router)
    }
  }


  def statusRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(m => (m.loggedInUserPot, m.airportConfig))

    staticRoute("#status", FeedsStatusLoc) ~> render {
      println(s"StatusLoc route")
      proxy(p => FeedsStatusPage(p()._1, p()._2))
    }
  }

  def forecastFileUploadRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(_.airportConfig)

    staticRoute("#forecastFileUpload", ForecastFileUploadLoc) ~> renderR(_ => proxy(ac => ForecastUploadComponent(ac())))
  }

  def portConfigRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._
    val proxy = SPACircuit.connect(m =>
      PortConfigPage.Props(m.redListUpdates, m.egateBanksUpdates, m.slaConfigs, m.loggedInUserPot, m.airportConfig, m.gateStandWalkTime)
    )
    staticRoute("#config", PortConfigLoc) ~> render(proxy(props => PortConfigPage(props())))
  }

  def dashboardRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(_.airportConfig)

    dynamicRouteCT(("#portDashboard" / int.option).caseClass[PortDashboardLoc]) ~>
      dynRenderR { case (page: PortDashboardLoc, router) =>
        proxy(p => PortDashboardPage(router, page, p()))
      }
  }

  def trainingHubRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(m => (m.loggedInUserPot, m.airportConfig))

    dynamicRouteCT(
      ("#trainingHub" / string("[a-zA-Z0-9]*")).caseClass[TrainingHubLoc]) ~>
      dynRenderR { case (page: TrainingHubLoc, router) =>
        proxy { p =>
          val props = TrainingHubComponent.Props(trainingHubLoc = page, router, p()._1, p()._2)
          ThemeProvider(DrtTheme.theme)(TrainingHubComponent(props))
        }
      }
  }

  def terminalRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val requiredTerminalName = string("[a-zA-Z0-9]+")
    val requiredTopLevelTab = string("[a-zA-Z0-9]+")
    val requiredSecondLevelTab = string("[a-zA-Z0-9]+")

    dynamicRouteCT(
      ("#terminal" / requiredTerminalName / requiredTopLevelTab / requiredSecondLevelTab / "" ~ queryToMap)
        .caseClass[TerminalPageTabLoc]) ~>
      dynRenderR { case (page: TerminalPageTabLoc, router) =>
        println(s"TerminalPageTabLoc loc: $page")
        println(s"TerminalPageTabLoc router: $router")
        val props = TerminalComponent.Props(terminalPageTab = page, router)
        ThemeProvider(DrtTheme.theme)(TerminalComponent(props))
      }
  }

  val pathToThisApp: String = dom.document.location.pathname

  val rootDomain: String = dom.document.location.host.split("\\.").drop(1).mkString(".")

  val useHttps: Boolean = dom.document.location.protocol == "https:"

  console.log(s"useHttps: '$useHttps'")

  val urls: Urls = Urls(rootDomain, useHttps)

  def absoluteUrl(relativeUrl: String): String = {
    if (pathToThisApp == "/") s"/$relativeUrl"
    else s"$pathToThisApp/$relativeUrl"
  }

  def exportUrl(exportType: ExportType, viewMode: ViewMode, terminal: Terminal): String = viewMode match {
    case ViewDay(localDate, Some(tmDate)) =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/snapshot/$localDate/${tmDate.millisSinceEpoch}/$terminal")
    case view =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/${view.dayStart.toLocalDate.toISOString}/${view.dayEnd.toLocalDate.toISOString}/$terminal")
  }

  def exportDatesUrl(exportType: ExportType, start: LocalDate, end: LocalDate, terminal: Terminal): String =
    SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/${start.toISOString}/${end.toISOString}/$terminal")

  @JSExportTopLevel("SPAMain")
  protected def getInstance(): this.type = this

  @JSExport
  def main(args: Array[String]): Unit = {
    log.debug("Application starting")

    ErrorHandler.registerGlobalErrorHandler()

    import scalacss.ScalaCssReact._

    GlobalStyles.addToDocument()
    DefaultFormFieldsStyle.addToDocument()
    DefaultToolTipsStyle.addToDocument()
    ArrivalsPageStylesDefault.addToDocument()
    DefaultScenarioSimulationStyle.addToDocument()

    sendInitialRequests()

    val router = Router(BaseUrl.until_#, routerConfig.logToConsole)
    router().renderIntoDOM(dom.document.getElementById("root"))
  }
}
