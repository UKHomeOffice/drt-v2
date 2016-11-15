package spatutorial.client

import chandu0101.scalajs.react.components.ReactTable
import diode.data.PotState.PotReady
import diode.{Effect, ModelR, UseValueEq, react}
import diode.data.{Empty, Pot, PotState, Ready}
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.CompScope.DuringCallbackU
import japgolly.scalajs.react.ReactComponentB.PSB
import japgolly.scalajs.react.ReactComponentC.ReqProps
import japgolly.scalajs.react.extra.router.StaticDsl.{DynamicRouteB, Rule}
import japgolly.scalajs.react.{ReactDOM, _}
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom
import spatutorial.client.components.TableTerminalDeskRecs.{QueueDetailsRow, TerminalUserDeskRecsRow}
import spatutorial.client.components.DeskRecsTable.UserDeskRecsRow
import spatutorial.client.components.{DeskRecsChart, GlobalStyles, Layout, MainMenu, QueueUserDeskRecsComponent, TableTerminalDeskRecs, TerminalPage}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.modules.FlightsView._
import spatutorial.client.modules.{FlightsView, _}
import spatutorial.client.services.HandyStuff.{CrunchResultAndDeskRecs, QueueUserDeskRecs}
import spatutorial.client.services._
import spatutorial.shared._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}

import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scalacss.Defaults._
import spatutorial.shared.HasAirportConfig



object TableViewUtils {

  def queueNameMapping = Map("eeaDesk" -> "EEA", "nonEeaDesk" -> "Non-EEA", "eGate" -> "e-Gates")

  def terminalUserDeskRecsRows(timestamps: Seq[Long], paxload: Map[String, List[Double]], queueCrunchResultsForTerminal: Map[QueueName, Pot[CrunchResultAndDeskRecs]], simulationResult: Map[QueueName, Pot[SimulationResult]]): List[TerminalUserDeskRecsRow] = {
    val queueNames: List[QueueName] = queueNameMapping.keys.toList
    val queueRows: List[List[((Long, QueueName), QueueDetailsRow)]] = queueNames.map(queueName => {
      simulationResult.get(queueName) match {
        case Some(Ready(sr)) =>
          log.info(s"^^^^^^^^^^^^^^^^^^^^^^^^ Ready")
          queueDetailsRowsFromNos(queueName, queueNosFromSimulationResult(timestamps, paxload, queueCrunchResultsForTerminal, simulationResult, queueName))
        case None =>
          queueCrunchResultsForTerminal.get(queueName) match {
            case Some(Ready(cr)) =>
              log.info(s"^^^^^^^^^^^^^^^^^^^^^^^^ queue crunch Ready4")
              queueDetailsRowsFromNos(queueName, queueNosFromCrunchResult(timestamps, paxload, queueCrunchResultsForTerminal, queueName))
            case _ =>
              log.info(s"^^^^^^^^^^^^^^^^^^^^^^^^ queue crunch Not ready")
              List()
          }
      }
    })

    val queueRowsByTime = queueRows.flatten.groupBy(tqr => tqr._1._1)

    queueRowsByTime.map((queueRows: (Long, List[((Long, QueueName), QueueDetailsRow)])) => {
      val qr = queueRows._2.map(_._2)
      TerminalUserDeskRecsRow(queueRows._1, qr)
    }).toList.sortWith(_.time < _.time)
  }

  def queueDetailsRowsFromNos(qn: QueueName, queueNos: Seq[List[Long]]): List[((Long, String), QueueDetailsRow)] = {
    queueNos.toList.transpose.zipWithIndex.map {
      case ((timestamp :: pax :: _ :: crunchDeskRec :: userDeskRec :: waitTimeCrunch :: waitTimeUser :: Nil), rowIndex) =>
        (timestamp, qn) -> QueueDetailsRow(
          timestamp = timestamp,
          pax = pax.toDouble,
          crunchDeskRec = crunchDeskRec.toInt,
          userDeskRec = DeskRecTimeslot(rowIndex.toString, userDeskRec.toInt),
          waitTimeWithCrunchDeskRec = waitTimeCrunch.toInt,
          waitTimeWithUserDeskRec = waitTimeUser.toInt,
          qn
        )
    }
  }

  def queueNosFromSimulationResult(timestamps: Seq[Long], paxload: Map[String, List[Double]], queueCrunchResultsForTerminal: Map[QueueName, Pot[(Pot[CrunchResult], Pot[UserDeskRecs])]], simulationResult: Map[QueueName, Pot[SimulationResult]], qn: QueueName): Seq[List[Long]] = {
    Seq(
      DeskRecsChart.takeEvery15th(timestamps).take(96).toList,
      paxload(qn).grouped(15).map(paxes => paxes.sum.toLong).toList,
      simulationResult(qn).get.recommendedDesks.map(rec => rec.time).grouped(15).map(_.min).toList,
      queueCrunchResultsForTerminal(qn).get._1.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      simulationResult(qn).get.recommendedDesks.map(rec => rec.desks).map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(qn).get._1.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      simulationResult(qn).get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    )
  }

  def queueNosFromCrunchResult(timestamps: Seq[Long], paxload: Map[String, List[Double]], queueCrunchResultsForTerminal: Map[QueueName, Pot[(Pot[CrunchResult], Pot[UserDeskRecs])]], qn: QueueName): Seq[List[Long]] = {
    Seq(
      DeskRecsChart.takeEvery15th(timestamps).take(96).toList,
      paxload(qn).grouped(15).map(paxes => paxes.sum.toLong).toList,
      List.range(0, queueCrunchResultsForTerminal(qn).get._1.get.recommendedDesks.length, 15).map(_.toLong),
      queueCrunchResultsForTerminal(qn).get._1.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(qn).get._1.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(qn).get._1.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(qn).get._1.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    )
  }

}

@JSExport("SPAMain")
object SPAMain extends js.JSApp {

  // Define the locations (pages) used in this application
  sealed trait Loc

  case object DashboardLoc extends Loc

  case object FlightsLoc extends Loc

  case object UserDeskRecommendationsLoc extends Loc

  case class TerminalUserDeskRecommendationsLoc(terminalName: TerminalName) extends Loc

  case class TerminalLoc(id: String) extends Loc

  SPACircuit.dispatch(GetWorkloads("", ""))
  SPACircuit.dispatch(GetAirportConfig())


  import scala.scalajs.js.timers._
  import scala.concurrent.duration._
  import scala.concurrent.duration.FiniteDuration

  SPACircuit.dispatch(GetLatestCrunch())

  setInterval(FiniteDuration(10L, SECONDS)) {
    SPACircuit.dispatch(GetLatestCrunch())
  }

  setInterval(FiniteDuration(10L, SECONDS)) {
    SPACircuit.dispatch(RequestFlights(0, 0))
  }
  // configure the router
  val routerConfig = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._
    val dashboardModelsConnect = SPACircuit.connect(m =>
      DashboardModels(m.workload, m.queueCrunchResults, m.simulationResult, m.userDeskRec))
    val airportConfigPotRCP: ReactConnectProxy[Pot[AirportConfig]] = SPACircuit.connect(_.airportConfig)

    val dashboardRoute = staticRoute(root, DashboardLoc) ~>
      renderR(ctl => dashboardModelsConnect(proxy => {
        log.info("dashboard update")
        airportConfigPotRCP(airportConfigPotMP =>
          Dashboard(ctl, proxy, airportConfigPotMP())
        )
      }))

    val flightsRoute = staticRoute("#flights", FlightsLoc) ~>
      renderR(ctl => {
        val airportWrapper = SPACircuit.connect(_.airportInfos)
        val flightsWrapper = SPACircuit.connect(m => m.flights)
        airportWrapper(airportInfoProxy => flightsWrapper(proxy => FlightsView(Props(proxy.value, airportInfoProxy.value))))
      })


    val terminals = dynamicRouteCT("#terminal" / string("[a-zA-Z0-9]+")
      .caseClass[TerminalLoc]) ~> dynRenderR((page: TerminalLoc, ctl) => TerminalPage(page, ctl))

    val userDeskRecsRoute = staticRoute("#userdeskrecs", UserDeskRecommendationsLoc) ~> renderR(ctl => {
      //todo take the queuenames from the workloads response
      log.info("running our user desk recs route")

      QueueUserDeskRecsComponent.terminalQueueUserDeskRecsComponent()
    })

    val rule = (dashboardRoute | flightsRoute | userDeskRecsRoute | terminals)
    rule.notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
  }.renderWith(layout)

  // base layout for all pages
  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = {
    Layout(c, r)
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
