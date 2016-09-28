package spatutorial.client

import chandu0101.scalajs.react.components.ReactTable
import diode.data.{Empty, Pot}
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.{ReactDOM, _}
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom
import spatutorial.client.components.{GlobalStyles, QueueUserDeskRecsComponent}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.modules.FlightsView._
import spatutorial.client.modules.{FlightsView, _}
import spatutorial.client.services.{SPACircuit, UserDeskRecs}
import spatutorial.shared.CrunchResult
import spatutorial.shared.FlightsApi.QueueName

import scala.collection.immutable.IndexedSeq
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scalacss.Defaults._

@JSExport("SPAMain")
object SPAMain extends js.JSApp {

  // Define the locations (pages) used in this application
  sealed trait Loc

  case object DashboardLoc extends Loc

  case object FlightsLoc extends Loc

  case object UserDeskRecommendationsLoc extends Loc

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
          FlightsView(Props(ctl, proxy, airportInfoProxy), proxy)))
      )

    val todosRoute = staticRoute("#userdeskrecs", UserDeskRecommendationsLoc) ~> renderR(ctl => {
      //todo take the queuenames from the workloads response
      val queues: Seq[QueueName] = Seq(eeadesk, egate)
      val queueUserDeskRecProps = queues.map { queueName =>
        val labels: ReactConnectProxy[Pot[IndexedSeq[String]]] = SPACircuit.connect(_.workload.map(_.labels))
        val queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]] = SPACircuit.connect(_.queueCrunchResults.getOrElse(queueName, Empty).flatMap(_._1))
        val queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]] = SPACircuit.connect(_.userDeskRec.getOrElse(queueName, Empty))
        val simulationResultWrapper = SPACircuit.connect(_.simulationResult.getOrElse(queueName, Empty))
        QueueUserDeskRecsComponent.Props(queueName, labels, queueCrunchResults, queueUserDeskRecs, simulationResultWrapper)
      }
      <.div(queueUserDeskRecProps.map(QueueUserDeskRecsComponent.component(_)))
    })

    (dashboardRoute | flightsRoute | todosRoute).notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
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
