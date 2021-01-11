package drt.client

import java.util.UUID

import diode.Action
import drt.client.actions.Actions._
import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType}
import drt.client.components.{AlertsPage, ContactPage, EditKeyCloakUserPage, FaqsPage, ForecastFileUploadPage, GlobalStyles, KeyCloakUsersPage, Layout, PortConfigPage, PortDashboardPage, StatusPage, TerminalComponent, TerminalPlanningComponent, UserDashboardPage}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.services.handlers.GetFeedSourceStatuses
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom
import org.scalajs.dom.console
import scalacss.ProdDefaults._
import uk.gov.homeoffice.drt.Urls

import scala.collection.immutable.Seq
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

object SPAMain {

  sealed trait Loc

  sealed trait UrlParameter {
    val name: String
    val value: Option[String]
  }

  object UrlDateParameter {
    val paramName = "date"

    def apply(paramValue: Option[String]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = paramValue
    }
  }

  object UrlTimeRangeStart {
    val paramName = "timeRangeStart"

    def apply(paramValue: Option[String]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = paramValue
    }
  }

  object UrlTimeRangeEnd {
    val paramName = "timeRangeEnd"

    def apply(paramValue: Option[String]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = paramValue
    }
  }

  object UrlViewType {
    val paramName = "viewType"

    def apply(viewType: Option[ViewType]): UrlParameter = new UrlParameter {
      override val name: String = paramName
      override val value: Option[String] = viewType.map(_.queryParamsValue)
    }
  }

  case class TerminalPageTabLoc(terminalName: String,
                                mode: String = "dashboard",
                                subMode: String = "summary",
                                queryParams: Map[String, String] = Map.empty[String, String]
                               ) extends Loc {
    val terminal: Terminal = Terminal(terminalName)
    val date: Option[String] = queryParams.get(UrlDateParameter.paramName).filter(_.matches(".+"))
    val timeRangeStartString: Option[String] = queryParams.get(UrlTimeRangeStart.paramName).filter(_.matches("[0-9]+"))
    val timeRangeEndString: Option[String] = queryParams.get(UrlTimeRangeEnd.paramName).filter(_.matches("[0-9]+"))
    val viewType: ViewType = queryParams.get(UrlViewType.paramName).map(vt => if (ViewRecs.queryParamsValue == vt) ViewRecs else ViewDeps).getOrElse(ViewDeps)

    def viewMode: ViewMode = {
      (mode, date) match {
        case ("current", Some(dateString)) => ViewDay(parseDateString(dateString))
        case ("snapshot", dateStringOption) =>
          val pointInTimeMillis = dateStringOption.map(parseDateString).getOrElse(SDate.midnightThisMorning())
          ViewPointInTime(pointInTimeMillis)
        case _ => ViewLive
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

    def dateFromUrlOrNow: SDateLike = date.map(parseDateString).getOrElse(SDate.now())

    def updateRequired(p: TerminalPageTabLoc): Boolean = (terminal != p.terminal) || (date != p.date) || (mode != p.mode)

    def loadAction: Action = mode match {
      case "planning" =>
        GetForecastWeek(TerminalPlanningComponent.defaultStartDate(dateFromUrlOrNow), terminal)
      case "staffing" =>
        log.info(s"dispatching get shifts for month on staffing page")
        GetShiftsForMonth(dateFromUrlOrNow, terminal)
      case _ => SetViewMode(viewMode)
    }
  }

  def serverLogEndpoint: String = absoluteUrl("logging")

  case class PortDashboardLoc(period: Option[Int]) extends Loc

  case object StatusLoc extends Loc

  case object UserDashboardLoc extends Loc

  case object ContactUsLoc extends Loc

  case object PortConfigLoc extends Loc

  case object KeyCloakUsersLoc extends Loc

  case object ForecastFileUploadLoc extends Loc

  case object FaqsLoc extends Loc

  case object DeskAndQueuesLoc extends Loc

  case object ArrivalsFaqsLoc extends Loc

  case object PortConfigurationFaqsLoc extends Loc

  case object StaffMovementsFaqsLoc extends Loc

  case object MonthlyStaffingFaqsLoc extends Loc

  case class KeyCloakUserEditLoc(userId: UUID) extends Loc

  case object AlertLoc extends Loc

  def requestInitialActions(): Unit = {
    val initActions = Seq(
      GetApplicationVersion,
      GetContactDetails,
      GetLoggedInUser,
      GetUserHasPortAccess,
      GetLoggedInStatus,
      GetAirportConfig,
      UpdateMinuteTicker,
      GetFeedSourceStatuses(),
      GetAlerts(0L),
      GetShowAlertModalDialog,
      GetOohStatus,
      GetFeatureFlags
    )

    initActions.foreach(SPACircuit.dispatch(_))
  }

  val routerConfig: RouterConfig[Loc] = RouterConfigDsl[Loc]
    .buildConfig { dsl: RouterConfigDsl[Loc, Unit] =>
      import dsl._

      val rule = homeRoute(dsl) |
        dashboardRoute(dsl) |
        terminalRoute(dsl) |
        statusRoute(dsl) |
        keyCloakUsersRoute(dsl) |
        keyCloakUserEditRoute(dsl) |
        alertRoute(dsl) |
        faqsRoute(dsl) |
        deskAndQueuesFaqsRoute(dsl) |
        arrivalsFaqsRoute(dsl) |
        portConfigurationFaqsRoute(dsl) |
        staffMovementsFaqsRoute(dsl) |
        monthlyStaffingFaqsRoute(dsl) |
        contactRoute(dsl) |
        portConfigRoute(dsl) |
        forecastFileUploadRoute(dsl)

      rule.notFound(redirectToPage(PortDashboardLoc(None))(Redirect.Replace))
    }
    .renderWith(Layout(_, _))
    .onPostRender((maybePrevLoc, currentLoc) => {
      Callback(
        (maybePrevLoc, currentLoc) match {
          case (Some(p: TerminalPageTabLoc), c: TerminalPageTabLoc) =>
            if (c.updateRequired(p)) SPACircuit.dispatch(c.loadAction)
          case (_, c: TerminalPageTabLoc) =>
            SPACircuit.dispatch(c.loadAction)
          case (_, UserDashboardLoc) =>
            SPACircuit.dispatch(GetUserDashboardState)
          case (_, KeyCloakUsersLoc) =>
            SPACircuit.dispatch(GetKeyCloakUsers)
          case (_, KeyCloakUserEditLoc(userId)) =>
            SPACircuit.dispatch(GetUserGroups(userId))
          case _ =>
        }
      )
    })

  def homeRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute(root, UserDashboardLoc) ~> renderR((router: RouterCtl[Loc]) => UserDashboardPage(router))
  }

  def contactUsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute(root, ContactUsLoc) ~> renderR(_ => ContactPage())
  }

  def statusRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#status", StatusLoc) ~> renderR((_: RouterCtl[Loc]) => StatusPage())
  }

  def keyCloakUsersRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#users", KeyCloakUsersLoc) ~> renderR((router: RouterCtl[Loc]) => KeyCloakUsersPage(router))
  }

  def keyCloakUserEditRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    dynamicRouteCT(("#editUser" / uuid).caseClass[KeyCloakUserEditLoc]) ~>
      dynRenderR((page: KeyCloakUserEditLoc, _) => {
        EditKeyCloakUserPage(page.userId)
      })
  }

  def alertRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#alerts", AlertLoc) ~> renderR((_: RouterCtl[Loc]) => AlertsPage())
  }

  def contactRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#contact", ContactUsLoc) ~> renderR(_ => ContactPage())
  }


  def faqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs", FaqsLoc) ~> renderR(_ => FaqsPage(""))
  }

  def deskAndQueuesFaqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs/deskAndQueues", DeskAndQueuesLoc) ~> renderR(_ => FaqsPage("deskAndQueues"))
  }

  def arrivalsFaqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs/arrivals", ArrivalsFaqsLoc) ~> renderR(_ => FaqsPage("arrivals"))
  }

  def portConfigurationFaqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs/portConfiguration", PortConfigurationFaqsLoc) ~> renderR(_ => FaqsPage("portConfiguration"))
  }

  def staffMovementsFaqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs/staff-movements", StaffMovementsFaqsLoc) ~> renderR(_ => FaqsPage("staff-movements"))
  }

  def monthlyStaffingFaqsRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#faqs/monthly-staffing", MonthlyStaffingFaqsLoc) ~> renderR(_ => FaqsPage("monthly-staffing"))
  }

  def forecastFileUploadRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#forecastFileUpload", ForecastFileUploadLoc) ~> renderR(_ => ForecastFileUploadPage())
  }

  def portConfigRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    staticRoute("#config", PortConfigLoc) ~> renderR(_ => PortConfigPage())
  }

  def dashboardRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    dynamicRouteCT(("#portDashboard" / int.option).caseClass[PortDashboardLoc]) ~>
      dynRenderR((page: PortDashboardLoc, router) => {
        PortDashboardPage(router, page)
      })
  }

  def terminalRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {
    import dsl._

    val requiredTerminalName = string("[a-zA-Z0-9]+")
    val requiredTopLevelTab = string("[a-zA-Z0-9]+")
    val requiredSecondLevelTab = string("[a-zA-Z0-9]+")

    dynamicRouteCT(
      ("#terminal" / requiredTerminalName / requiredTopLevelTab / requiredSecondLevelTab / "" ~ queryToMap).caseClass[TerminalPageTabLoc]) ~>
      dynRenderR((page: TerminalPageTabLoc, router) => {
        val props = TerminalComponent.Props(terminalPageTab = page, router)
        TerminalComponent(props)
      })
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

  def exportDesksUrl(exportType: ExportType, viewMode: ViewMode, terminal: Terminal): String = viewMode match {
    case view: ViewPointInTime =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/snapshot/${view.time.toLocalDate}/${view.millis}/$terminal")
    case view =>
      SPAMain.absoluteUrl(s"export/${exportType.toUrlString}/${view.dayStart.toLocalDate.toISOString}/${view.dayEnd.toLocalDate.toISOString}/$terminal")
  }

  def exportArrivalViewUrl(viewMode: ViewMode, terminal: Terminal): String = viewMode match {
    case view: ViewPointInTime =>
      SPAMain.absoluteUrl(s"export/arrivals/snapshot/${view.time.toLocalDate}/${view.millis}/$terminal")
    case view =>
      SPAMain.absoluteUrl(s"export/arrivals/${view.dayStart.toLocalDate.toISOString}/${view.dayEnd.toLocalDate.toISOString}/$terminal")
  }

  def assetsPrefix: String = if (pathToThisApp == "/") s"/assets" else s"live/assets"

  @JSExportTopLevel("SPAMain")
  protected def getInstance(): this.type = this

  @JSExport
  def main(args: Array[String]): Unit = {
    log.debug("Application starting")

    ErrorHandler.registerGlobalErrorHandler()

    import scalacss.ScalaCssReact._

    GlobalStyles.addToDocument()

    requestInitialActions()

    val router = Router(BaseUrl.until_#, routerConfig.logToConsole)
    router().renderIntoDOM(dom.document.getElementById("root"))
  }
}
