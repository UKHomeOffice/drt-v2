package drt.client

import drt.client.actions.Actions._
import drt.client.components.{GlobalStyles, Layout, TerminalComponent, TerminalsDashboardPage}
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, ViewDay, ViewLive, ViewPointInTime}
import japgolly.scalajs.react.WebpackRequire
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
                               ) extends Loc

  case class TerminalsDashboardLoc(hours: Int) extends Loc

  def requestInitialActions() = {
    val initActions = Seq(
      GetAirportConfig(),
      GetCrunchState(),
      GetShifts(),
      GetFixedPoints(),
      GetStaffMovements()
    )

    initActions.foreach(SPACircuit.dispatch(_))
  }

  val routerConfig: RouterConfig[Loc] = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._

    val home: dsl.Rule = staticRoute(root, TerminalsDashboardLoc(3)) ~> renderR((_: RouterCtl[Loc]) => TerminalsDashboardPage(3))
    val terminalsDashboard: dsl.Rule = dynamicRouteCT("#terminalsDashboard" / int.caseClass[TerminalsDashboardLoc]) ~>
      dynRenderR((page: TerminalsDashboardLoc, ctl) => {
        TerminalsDashboardPage(page.hours)
      })
    val terminal: dsl.Rule = dynamicRouteCT(("#terminal" / string("[a-zA-Z0-9]+") / string("[a-zA-Z0-9]+") / string("[a-zA-Z0-9]+") / string(".+").option).caseClassDebug[TerminalPageTabLoc]) ~>
      dynRenderR((page: TerminalPageTabLoc, router) => {
        log.info(s"Got this page: $page")

        updateModelFromUri(page)

        val props = TerminalComponent.Props(terminalPageTab = page, router)
        TerminalComponent(props)
      })

    val rule = home | terminal | terminalsDashboard
    rule.notFound(redirectToPage(TerminalsDashboardLoc(3))(Redirect.Replace))
  }.renderWith(layout)

  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = Layout(c, r)

  def pathToThisApp: String = dom.document.location.pathname

  def viewModeFromModelAndUrl(page: TerminalPageTabLoc) = {
    val vm = page match {
      case TerminalPageTabLoc(_, "current", _, Some(dateString)) =>
        ViewDay(SDate(dateString))
      case TerminalPageTabLoc(_, "snapshot", _, dateStringOption) =>
        ViewPointInTime(dateStringOption.map(SDate(_)).getOrElse(SDate.now()))
      case _ =>
        ViewLive()
    }

    vm
  }

  def updateModelFromUri(page: TerminalPageTabLoc) = {
    SPACircuit.dispatch(SetViewMode(viewModeFromModelAndUrl(page)))
  }

  def require(): Unit = {
    WebpackRequire.React
    WebpackRequire.ReactDOM
    ()
  }

  @JSExport
  def main(): Unit = {
    require()

    log.info(s"think the port is ${pathToThisApp.split("/")}")
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
