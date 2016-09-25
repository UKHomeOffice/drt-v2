package spatutorial.client

import chandu0101.scalajs.react.components.{Spinner, ReactTable}
import diode.ModelR
import diode.data.{Empty, Pot}
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.ReactDOM
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components.{DeskRecsChart, GlobalStyles}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.{QueueCrunchResults, DashboardModels}
import spatutorial.client.modules.FlightsView
import spatutorial.client.modules._
import spatutorial.client.services.{QueueName, UserDeskRecs, RootModel, SPACircuit}
import spatutorial.shared.{SimulationResult, CrunchResult}
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

  val eeadesk = "eeaDesk"
  val egate = "eGate"

  // configure the router
  // configure the router
  val routerConfig = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._
    val dashboardModelsConnect = SPACircuit.connect(m =>
      DashboardModels(m.workload, m.queueCrunchResults, m.simulationResult, m.userDeskRec))

    val dashboardRoute = staticRoute(root, DashboardLoc) ~>
      renderR(ctl => dashboardModelsConnect(proxy => {
        log.info("dashboard update")
        Dashboard(ctl, proxy)
      }))

    val flightsRoute = staticRoute("#flights", FlightsLoc) ~>
      renderR(ctl => SPACircuit.wrap(_.airportInfos)(airportInfoProxy =>
        SPACircuit.wrap(_.flights)(proxy =>
          FlightsView(FlightsView.Props(ctl, proxy, airportInfoProxy), proxy)))
      )

    val todosRoute = staticRoute("#todo", TodoLoc) ~> renderR(ctl => {
      //todo take the queuenames from the workloads response
      val queues: Seq[QueueName] = Seq(eeadesk, egate)
      val queueUserDeskRecProps = queues.map { queueName =>

        val queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]] = SPACircuit.connect(_.queueCrunchResults.getOrElse(queueName, Empty).flatMap(_._1))
        val queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]] = SPACircuit.connect(_.userDeskRec.getOrElse(queueName, Empty))
        val simulationResultWrapper = SPACircuit.connect(_.simulationResult.getOrElse(queueName, Empty))
        QueueUserDeskRecsComponent.Props(queueName, queueCrunchResults, queueUserDeskRecs, simulationResultWrapper)
      }
      <.div(queueUserDeskRecProps.map(QueueUserDeskRecsComponent.component(_)))
    })

    (dashboardRoute | flightsRoute | todosRoute).notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
  }.renderWith(layout)

  object QueueUserDeskRecsComponent {

    case class Props(queueName: QueueName,
                     queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]],
                     queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]],
                     simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]]
                    )

    val component = ReactComponentB[Props]("QueueUserDeskRecs")
      .render_P(props =>
        <.div(^.key := props.queueName,
          props.queueUserDeskRecs(allQueuesDeskRecs => UserDeskRecsComponent(props.queueName, allQueuesDeskRecs)),
          props.queueCrunchResults(crw =>
            props.simulationResultWrapper(srw => {
              log.info(s"running simresultchart again for $props.queueName")
              DeskRecsChart.userSimulationWaitTimesChart(props.queueName, Dashboard.labels, srw, crw)
            })))
      ).build
  }

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
    import scalacss.ScalaCssReact._
    ReactTable.DefaultStyle.addToDocument()
    //    Spinner.Style.addToDocument()
    GlobalStyles.addToDocument()
    // create the router
    val router = Router(BaseUrl.until_#, routerConfig)
    // tell React to render the router in the document body
    ReactDOM.render(router(), dom.document.getElementById("root"))
  }
}
