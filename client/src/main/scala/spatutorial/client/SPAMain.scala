package spatutorial.client

import diode.ModelR
import diode.data.Pot
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.ReactDOM
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components.{DeskRecsChart, GlobalStyles}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.modules.FlightsView
import spatutorial.client.modules._
import spatutorial.client.services.{RootModel, SPACircuit}
import spatutorial.shared.FlightsApi.Flights
import diode.react.ReactPot._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scalacss.Defaults._
import scalacss.ScalaCssReact._
import japgolly.scalajs.react
import japgolly.scalajs.react.vdom.DomCallbackResult._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{Callback, ReactComponentB, _}

@JSExport("SPAMain")
object SPAMain extends js.JSApp {

  // Define the locations (pages) used in this application
  sealed trait Loc

  case object DashboardLoc extends Loc

  case object FlightsLoc extends Loc

  case object TodoLoc extends Loc

  // configure the router
  // configure the router
  val routerConfig = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._
    val simulationResultWrapper = SPACircuit.connect(_.simulationResult)
    val crunchResultWrapper = SPACircuit.connect(_.crunchResult)
    val todoWrapper = SPACircuit.connect(_.userDeskRec)
    val dashboardModelsConnect = SPACircuit.connect(m =>
      DashboardModels(m.workload, m.crunchResult, m.simulationResult, m.userDeskRec))
    // wrap/connect components to the circuit
    (staticRoute(root, DashboardLoc) ~>
      renderR(ctl => dashboardModelsConnect(proxy => {
        log.info("dashboard update")
        //        workloadsWrapper()
        Dashboard(ctl, proxy)
      })) |
      (staticRoute("#flights", FlightsLoc) ~>
        renderR(ctl => SPACircuit.wrap(_.flights)(proxy =>
          FlightsView(FlightsView.Props(ctl, proxy), proxy)))
        ) |
      (staticRoute("#todo", TodoLoc) ~> renderR(ctl => {
        <.div(
          todoWrapper(UserDeskRecsComponent(_)),
          crunchResultWrapper(crw =>
            simulationResultWrapper(srw => {
            log.info("running simresultchart again")
            DeskRecsChart.userSimulationWaitTimesChart(Dashboard.labels, srw, crw)
          })))
      }))
      ).notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
  }.renderWith(layout)

  val todoCountWrapper: ReactConnectProxy[Option[Int]] = SPACircuit.connect(_.todos.map(_.items.length).toOption)

  // base layout for all pages
  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = {
    <.div(
      // here we use plain Bootstrap class names as these are specific to the top level layout defined here
      <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
        <.div(^.className := "container",
          <.div(^.className := "navbar-header", <.span(^.className := "navbar-brand", "DRT EDI Live Spike")),
          <.div(^.className := "collapse navbar-collapse",
            // connect menu to model, because it needs to update when the number of open todos changes
            todoCountWrapper(proxy => MainMenu(c, r.page, proxy))
          )
        )
      ),
      // currently active module is shown in this container
      <.div(^.className := "container", r.render())
    )
  }

  @JSExport
  def main(): Unit = {
    log.warn("Application starting")
    // send log messages also to the server
    log.enableServerLogging("/logging")
    log.info("This message goes to server as well")

    // create stylesheet
    GlobalStyles.addToDocument()
    // create the router
    val router = Router(BaseUrl.until_#, routerConfig)
    // tell React to render the router in the document body
    ReactDOM.render(router(), dom.document.getElementById("root"))
  }
}
