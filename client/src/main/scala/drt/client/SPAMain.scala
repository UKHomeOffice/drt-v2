package drt.client

import diode.Action
import drt.client.actions.Actions._
import drt.client.components.{GlobalStyles, Layout, TerminalComponent, TerminalPlanningComponent, TerminalsDashboardPage}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import japgolly.scalajs.react.{Callback, WebpackRequire}
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom

import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel, JSImport}
import scalacss.ProdDefaults._

@JSExportTopLevel("SPAMain")
object SPAMain extends js.JSApp {

  sealed trait Loc

  case class TerminalPageTabLoc(
                                 terminal: String,
                                 mode: String = "current",
                                 tab: String = "arrivals",
                                 date: Option[String] = None
                               ) extends Loc {
    def viewMode: ViewMode = {
      (mode, date) match {
        case ("current", Some(dateString)) =>
          ViewDay(SDate(dateString))
        case ("snapshot", dateStringOption) =>
          ViewPointInTime(dateStringOption.map(SDate(_)).getOrElse(SDate.now()))
        case _ =>
          ViewLive()
      }
    }

    def updateRequired(p: TerminalPageTabLoc) = (terminal != p.terminal) || (date != p.date) || (mode != p.mode)

    def loadAction: Action = mode match {
      case "planning" =>
        GetForecastWeek(TerminalPlanningComponent.defaultStartDate(date), terminal)
      case _ => SetViewMode(viewMode)
    }
  }

  case class TerminalsDashboardLoc(startTime: Option[String]) extends Loc

  def requestInitialActions(): Unit = {
    val initActions = Seq(
      GetAirportConfig(),
      GetCrunchState(),
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
      val terminalsDashboard: dsl.Rule = dynamicRouteCT(("#terminalsDashboard" / string(".+").option).caseClass[TerminalsDashboardLoc]) ~>
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
    .onPostRender((prev, current) => {
      Callback(
        (prev, current) match {
          case (Some(p: TerminalPageTabLoc), c: TerminalPageTabLoc) =>
            if (c.updateRequired(p)) SPACircuit.dispatch(c.loadAction)
          case (_, c: TerminalPageTabLoc) =>
            SPACircuit.dispatch(c.loadAction)
          case _ =>
        }
      )
    })

  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = Layout(c, r)

  def pathToThisApp: String = dom.document.location.pathname

  def require(): Unit = {
    WebpackRequire.React
    WebpackRequire.ReactDOM
    ()
  }

  @JSExport
  def main(): Unit = {
    require()
    log.warn("Application starting")

    import scalacss.ScalaCssReact._

    GlobalStyles.addToDocument()

    requestInitialActions()

    val router = Router(BaseUrl.until_#, routerConfig.logToConsole)
    router().renderIntoDOM(dom.document.getElementById("root"))
  }
}

object WebpackBootstrapRequire {

  @JSImport("expose-loader?jQuery!jquery", JSImport.Namespace)
  @js.native
  object jQuery extends js.Any

  @JSImport("expose-loader?Bootstrap!bootstrap", JSImport.Namespace)
  @js.native
  object Bootstrap extends js.Any

}
