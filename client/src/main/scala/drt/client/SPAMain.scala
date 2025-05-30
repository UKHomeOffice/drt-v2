package drt.client

import diode.Action
import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.actions.Actions._
import drt.client.components.TerminalDesksAndQueues.{ChartsView, Deployments, DeskType, DisplayType, Hourly, Ideal, Quarterly, TableView, TimeInterval}
import drt.client.components.styles._
import drt.client.components.{AccessibilityStatementComponent, FeedsStatusPage, ForecastUploadComponent, GlobalStyles, IAccessibilityStatementProps, Layout, PortConfigPage, PortDashboardPage, TerminalComponent, TrainingHubComponent, UserDashboardPage}
import drt.client.logger._
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.services.handlers.{GetFeedSourceStatuses, GetUserPreferences}
import drt.client.spa.TerminalPageModes.{Current, Shifts, Staffing}
import drt.client.spa.{TerminalPageMode, TerminalPageModes}
import drt.shared.DrtPortConfigs
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom
import org.scalajs.dom.{console, window}
import scalacss.ProdDefaults._
import uk.gov.homeoffice.drt.Urls
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scala.util.Try

object SPAMain {
  sealed trait Loc {
    val url: String

    def href: String = window.location.href.split("#").headOption match {
      case Some(head) => head + url
      case None => url
    }

    val portCodeStr: String = dom.document.getElementById("port-code").getAttribute("value")
    val portConfig: AirportConfig = DrtPortConfigs.confByPort(PortCode(portCodeStr))

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

  object UrlTimeSelectedParameter extends UrlDateLikeParameter {
    override val paramName = "time"
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

  object UrlDayRangeType {
    val paramName = "dayRange"

    def apply(viewType: Option[String]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = viewType
    }
  }

  object AccessibilityStatementLoc {
    val hashValue: String = "#accessibility"
  }

  case class AccessibilityStatementLoc(section: Option[String] = None) extends Loc {
    override val url: String = section match {
      case Some(s) => s"$AccessibilityStatementLoc.hashValue/$s"
      case None => AccessibilityStatementLoc.hashValue
    }

    override def title(maybeTerminal: Option[Terminal]): String = title("Accessibility Statement", maybeTerminal)
  }

  object TerminalPageTabLoc {
    val hashValue: String = "#terminal"

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
    private val queryString = if (queryParams.nonEmpty) s"?${queryParams.map { case (k, v) => s"$k=$v" }.mkString("&")}" else ""
    override val url = s"${TerminalPageTabLoc.hashValue}/$terminalName/$modeStr/$subMode$queryString"

    def pageName = (modeStr.toLowerCase, subMode.toLowerCase) match {
      case ("current", "arrivals") => "Arrivals"
      case ("current", "desksandqueues") => "Desks and queues"
      case ("current", "staffing") => "Staff movements"
      case ("current", "simulations") => "Simulate day"
      case ("dashboard", "summary") => "Terminal dashboard"
      case ("planning", _) => "Staff planning"
      case ("staffing", _) => "Monthly staffing"
      case ("shifts", _) => "Shifts"
      case _ => ""
    }

    override def title(maybeTerminal: Option[Terminal]): String = title(pageName, maybeTerminal)

    val terminal: Terminal = Terminal(terminalName)

    def dayRangeType = queryParams.get(UrlDayRangeType.paramName)

    val maybeViewDate: Option[LocalDate] = queryParams.get(UrlDateParameter.paramName)
      .filter(_.matches(".+"))
      .flatMap(dateStr => Try {
        val parts = dateStr.split("-")
        LocalDate(parts(0).toInt, parts(1).toInt, parts(2).toInt)
      }.toOption)
    val maybeTimeMachineDate: Option[SDateLike] = queryParams.get(UrlTimeMachineDateParameter.paramName)
      .filter(_.matches(".+"))
      .flatMap(dateStr => Try(parseDateString(dateStr)).toOption)
    val timeSelectString: Option[String] = queryParams.get(UrlTimeSelectedParameter.paramName)
    val timeRangeStartString: Option[String] = queryParams.get(UrlTimeRangeStart.paramName).filter(_.matches("[0-9]+:[0-9]+"))
    val timeRangeEndString: Option[String] = queryParams.get(UrlTimeRangeEnd.paramName).filter(_.matches("[0-9]+:[0-9]+[ +1]*"))

    val deskType: DeskType = queryParams.get(UrlViewType.paramName)
      .map(vt => if (Ideal.queryParamsValue == vt) Ideal else Deployments).getOrElse(Deployments)
    val displayAs: DisplayType = queryParams.get(UrlDisplayType.paramName)
      .map(vt => if (TableView.queryParamsValue == vt) TableView else ChartsView).getOrElse(TableView)
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

    def timeRangeStart: Option[String] = timeRangeStartString

    def timeRangeEnd: Option[String] = timeRangeEndString

    def dateFromUrlOrNow: SDateLike = maybeViewDate.map(ld => SDate(ld)).getOrElse(SDate.now())

    def updateRequired(p: TerminalPageTabLoc): Boolean =
      (terminal != p.terminal) || (maybeViewDate != p.maybeViewDate) || (mode != p.mode) || (maybeTimeMachineDate != p.maybeTimeMachineDate)

    def loadAction: Action = mode match {
      case Staffing | Shifts =>
        GetAllShiftAssignments
      case _ =>
        SetViewMode(viewMode)
    }

    def update(mode: TerminalPageMode, subMode: String, queryParams: Map[String, String] = Map[String, String]()): TerminalPageTabLoc =
      copy(modeStr = mode.asString, subMode = subMode, queryParams = queryParams)
  }

  def serverLogEndpoint: String = absoluteUrl("logging")

  object PortDashboardLoc {
    val hashValue: String = "#portDashboard"
  }

  case class PortDashboardLoc(period: Option[Int], subMode: Int = 60, queryParams: Map[String, String] = Map.empty[String, String]) extends Loc {
    private val queryString = if (queryParams.nonEmpty) s"?${queryParams.map { case (k, v) => s"$k=$v" }.mkString("&")}" else ""
    override val url = s"${PortDashboardLoc.hashValue}$period$subMode$queryString"

    override def title(maybeTerminal: Option[Terminal]): String = title("Dashboard", maybeTerminal)
  }

  case object StatusLoc extends Loc {
    val hashValue: String = "#status"
    override val url = s"$hashValue"

    override def title(maybeTerminal: Option[Terminal]): String = title("Feeds status", maybeTerminal)
  }

  case object UserDashboardLoc extends Loc {
    val hashValue: String = ""
    override val url = ""

    override def title(maybeTerminal: Option[Terminal]): String = title("Dashboard", maybeTerminal)
  }

  object TrainingHubLoc {
    val hashValue: String = "#trainingHub"
  }

  case class TrainingHubLoc(modeStr: String = "dropInBooking") extends Loc {
    override val url = s"${TrainingHubLoc.hashValue}/$modeStr"

    private val subTitle = modeStr match {
      case "dropInBooking" => "Book a drop-in"
      case "trainingMaterial" => "Training material"
      case _ => ""
    }

    override def title(maybeTerminal: Option[Terminal]): String = title(s"Training hub - $subTitle", maybeTerminal)
  }

  case object PortConfigLoc extends Loc {
    val hashValue: String = "#config"
    override val url = s"$hashValue"

    override def title(maybeTerminal: Option[Terminal]): String = title("Port config", maybeTerminal)
  }

  case object ForecastFileUploadLoc extends Loc {
    val hashValue: String = "#forecastFileUpload"
    override val url = s"$hashValue"

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
    GetUserPreferences
  )

  private def sendInitialRequests(): Unit = initialRequestsActions.foreach(SPACircuit.dispatch(_))

  val routerConfig: RouterConfig[Loc] = RouterConfigDsl[Loc]
    .buildConfig { dsl: RouterConfigDsl[Loc, Unit] =>
      import dsl._

      val rule = homeRoute(dsl) |
        accessibilityRoute(dsl) |
        dashboardRoute(dsl) |
        terminalRoute(dsl) |
        statusRoute(dsl) |
        trainingHubRoute(dsl) |
        portConfigRoute(dsl) |
        forecastFileUploadRoute(dsl)

      rule.notFound(redirectToPage(PortDashboardLoc(None))(SetRouteVia.HistoryReplace))
    }
    .renderWith(Layout(_, _))
    .setTitle(_.title(maybeTerminal))
    .setPostRender { (maybePrevLoc, currentLoc) =>
      val title = currentLoc.title(maybeTerminal)
      log.info(s"Sending page view: $title (${currentLoc.href})")
      Callback(GoogleEventTracker.sendPageView(title, currentLoc.href)) >>
        Callback(
          (maybePrevLoc, currentLoc) match {
            case (Some(p: TerminalPageTabLoc), c: TerminalPageTabLoc) =>
              if (c.updateRequired(p)) SPACircuit.dispatch(c.loadAction)
            case (_, c: TerminalPageTabLoc) =>
              SPACircuit.dispatch(c.loadAction)
            case (_, UserDashboardLoc) =>
              SPACircuit.dispatch(GetUserDashboardState)
            case _ =>
          }
        )
    }

  private def maybeTerminal: Option[Terminal] = {
    val terminalRegex = """.+terminal/([A-Z0-9]+)/.+""".r
    val url = window.location.href
    url match {
      case terminalRegex(t) => Some(Terminal(t))
      case _ => None
    }
  }

  private def sendReportProblemGaEvent(portCode: String) = {
    Callback(GoogleEventTracker.sendEvent(portCode, "Accessibility", "Email us to report a problem"))
  }

  private def accessibilityRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy: ReactConnectProxy[Pot[AirportConfig]] = SPACircuit.connect(_.airportConfig)

    dynamicRouteCT((AccessibilityStatementLoc.hashValue / string("[a-zA-Z0-9-]+").option).caseClass[AccessibilityStatementLoc]) ~>
      dynRenderR { case (page: AccessibilityStatementLoc, _) =>
        proxy(ac =>
          AccessibilityStatementComponent(
            IAccessibilityStatementProps(
              ac().map(_.contactEmail.toString).getOrElse(""),
              () => sendReportProblemGaEvent(ac().map(_.portCode.iata).getOrElse("")),
              page.section.getOrElse("")))
        )
      }
  }

  private def homeRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute(root, UserDashboardLoc) ~> renderR((router: RouterCtl[Loc]) => UserDashboardPage(router))
  }

  private def dashboardRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(_.airportConfig)

    dynamicRouteCT((PortDashboardLoc.hashValue / int.option / int / "" ~ queryToMap).caseClass[PortDashboardLoc]) ~>
      dynRenderR { case (page: PortDashboardLoc, router) =>
        proxy(_ => PortDashboardPage(router, page))
      }
  }

  private def terminalRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val requiredTerminalName = string("[a-zA-Z0-9]+")
    val requiredTopLevelTab = string("[a-zA-Z0-9]+")
    val requiredSecondLevelTab = string("[a-zA-Z0-9]+")

    dynamicRouteCT(
      (TerminalPageTabLoc.hashValue / requiredTerminalName / requiredTopLevelTab / requiredSecondLevelTab / "" ~ queryToMap).caseClass[TerminalPageTabLoc]) ~>
      dynRenderR { case (page: TerminalPageTabLoc, router) =>
        val props = TerminalComponent.Props(terminalPageTab = page, router)
        ThemeProvider(DrtReactTheme)(TerminalComponent(props))
      }
  }

  private def statusRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(m => (m.loggedInUserPot, m.airportConfig))

    staticRoute(StatusLoc.hashValue, StatusLoc) ~> renderR(_ => proxy(p => FeedsStatusPage(p()._1, p()._2)))
  }

  private def trainingHubRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(m => (m.loggedInUserPot, m.airportConfig))

    dynamicRouteCT(
      (TrainingHubLoc.hashValue / string("[a-zA-Z0-9]*")).caseClass[TrainingHubLoc]) ~>
      dynRenderR { case (page: TrainingHubLoc, router) =>
        proxy { p =>
          val props = TrainingHubComponent.Props(trainingHubLoc = page, router, p()._1, p()._2)
          ThemeProvider(DrtReactTheme)(TrainingHubComponent(props))
        }
      }
  }

  private def portConfigRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._
    val proxy = SPACircuit.connect(m =>
      PortConfigPage.Props(m.redListUpdates, m.egateBanksUpdates, m.slaConfigs, m.loggedInUserPot, m.airportConfig, m.gateStandWalkTime)
    )
    staticRoute(PortConfigLoc.hashValue, PortConfigLoc) ~> render(proxy(props => PortConfigPage(props())))
  }

  private def forecastFileUploadRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val proxy = SPACircuit.connect(_.airportConfig)

    staticRoute(ForecastFileUploadLoc.hashValue, ForecastFileUploadLoc) ~> renderR(_ => proxy(ac => ForecastUploadComponent(ac())))
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

  def exportUrl(exportType: ExportType, viewMode: ViewMode): String = viewMode match {
    case ViewDay(localDate, Some(tmDate)) =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/snapshot/$localDate/${tmDate.millisSinceEpoch}${exportType.maybeTerminal.map(t => s"/${t.toString}").getOrElse("")}")
    case view =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/${view.dayStart.toLocalDate.toISOString}/${view.dayEnd.toLocalDate.toISOString}${exportType.maybeTerminal.map(t => s"/${t.toString}").getOrElse("")}")
  }

  def exportDatesUrl(exportType: ExportType, start: LocalDate, end: LocalDate): String =
    SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/${start.toISOString}/${end.toISOString}${exportType.maybeTerminal.map(t => s"/${t.toString}").getOrElse("")}")

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
