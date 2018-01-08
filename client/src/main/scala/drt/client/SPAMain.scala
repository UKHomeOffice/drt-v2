package drt.client

import diode.Action
import drt.client.actions.Actions._
import drt.client.components.{GlobalStyles, Layout, TerminalComponent, TerminalPlanningComponent, TerminalStaffingV2, TerminalsDashboardPage}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom

import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scalacss.ProdDefaults._

@JSExportTopLevel("SPAMain")
object SPAMain extends js.JSApp {

  sealed trait Loc

  case class TerminalPageTabLoc(
                                 terminal: String,
                                 mode: String = "current",
                                 tab: String = "desksAndQueues",
                                 date: Option[String] = None
                               ) extends Loc {
    def viewMode: ViewMode = {
      (mode, date) match {
        case ("current", Some(dateString)) => ViewDay(SDate(dateString))
        case ("snapshot", dateStringOption) => ViewPointInTime(dateStringOption.map(SDate(_)).getOrElse(SDate.now()))
        case _ => ViewLive()
      }
    }

    def updateRequired(p: TerminalPageTabLoc): Boolean = (terminal != p.terminal) || (date != p.date) || (mode != p.mode)

    def loadAction: Action = mode match {
      case "planning" =>
        GetForecastWeek(TerminalPlanningComponent.defaultStartDate(date), terminal)
      case "staffing" =>
        log.info(s"dispatching get shifts for month on staffing page")
        GetShiftsForMonth(TerminalStaffingV2.dateFromDateStringOption(date))
      case _ => SetViewMode(viewMode)
    }
  }

  case class TerminalsDashboardLoc(period: Option[Int]) extends Loc

  def requestInitialActions(): Unit = {
    val initActions = Seq(
      GetAirportConfig(),
      GetShifts(),
      GetFixedPoints(),
      GetStaffMovements()
    )

    initActions.foreach(SPACircuit.dispatch(_))
  }

  val routerConfig: RouterConfig[Loc] = RouterConfigDsl[Loc]
    .buildConfig { dsl =>
      import dsl._

      val home: dsl.Rule = staticRoute(root, TerminalsDashboardLoc(None)) ~> renderR((router: RouterCtl[Loc]) => TerminalsDashboardPage(None, router))
      val terminalsDashboard: dsl.Rule = dynamicRouteCT(("#terminalsDashboard" / int.option).caseClass[TerminalsDashboardLoc]) ~>
        dynRenderR((page: TerminalsDashboardLoc, router) => {
          TerminalsDashboardPage(None, router, page)
        })
      val terminal: dsl.Rule = dynamicRouteCT(("#terminal" / string("[a-zA-Z0-9]+") / string("[a-zA-Z0-9]+") / string("[a-zA-Z0-9]+") / string(".+").option).caseClass[TerminalPageTabLoc]) ~>
        dynRenderR((page: TerminalPageTabLoc, router) => {
          val props = TerminalComponent.Props(terminalPageTab = page, router)
          TerminalComponent(props)
        })

      val rule = home | terminal | terminalsDashboard
      rule.notFound(redirectToPage(TerminalsDashboardLoc(None))(Redirect.Replace))
    }
    .renderWith(layout)
    .onPostRender((maybePrevLoc, currentLoc) => {
      Callback(
        (maybePrevLoc, currentLoc) match {
          case (Some(p: TerminalPageTabLoc), c: TerminalPageTabLoc) =>
            if (c.updateRequired(p)) SPACircuit.dispatch(c.loadAction)
          case (_, c: TerminalPageTabLoc) =>
            log.info(s"Triggering post load action for $c")
            SPACircuit.dispatch(c.loadAction)
          case (_, _: TerminalsDashboardLoc) =>
            SPACircuit.dispatch(SetViewMode(ViewLive()))
          case _ =>
        }
      )
    })

  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = Layout(c, r)

  def pathToThisApp: String = dom.document.location.pathname

  @JSExport
  def main(): Unit = {
    log.warn("Application starting")

    import scalacss.ScalaCssReact._

    GlobalStyles.addToDocument()

    requestInitialActions()

    val router = Router(BaseUrl.until_#, routerConfig.logToConsole)
    router().renderIntoDOM(dom.document.getElementById("root"))
  }
}
