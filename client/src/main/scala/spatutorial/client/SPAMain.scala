package spatutorial.client

import chandu0101.scalajs.react.components.ReactTable
import diode.data.PotState.PotReady
import diode.{ModelR, UseValueEq, react}
import diode.data.{Empty, Pot, PotState, Ready}
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.extra.router.StaticDsl.Rule
import japgolly.scalajs.react.{ReactDOM, _}
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom
import spatutorial.client.components.TableTerminalDeskRecs.{QueueDetailsRow, TerminalUserDeskRecsRow}
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.components.{DeskRecsChart, GlobalStyles, QueueUserDeskRecsComponent, TableTerminalDeskRecs}
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.DashboardModels
import spatutorial.client.modules.FlightsView._
import spatutorial.client.modules.{FlightsView, _}
import spatutorial.client.services.HandyStuff.{CrunchResultAndDeskRecs, QueueUserDeskRecs}
import spatutorial.client.services._
import spatutorial.shared._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}

import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scalacss.Defaults._

trait LHRAirportConfig {
  val terminalNames: Seq[TerminalName] = Seq("2", "3", "4", "5")
  val airportShortCode: String = "edi"
  val eeadesk = "eeaDesk"
  val egate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
  val queues: Seq[QueueName] = Seq(eeadesk, egate, nonEeaDesk)
}

trait AirportConfig {
  val terminalNames: Seq[TerminalName] = Seq("A1", "A2")
  val airportShortCode: String = "edi"
  val eeadesk = "eeaDesk"
  val egate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
  val queues: Seq[QueueName] = Seq(eeadesk, egate, nonEeaDesk)
}

object TableViewUtils {

  def queueNameMapping = Map("eeaDesk"->"EEA", "nonEeaDesk" -> "Non-EEA", "eGate" -> "e-Gates")

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
object SPAMain extends js.JSApp with LHRAirportConfig {

  // Define the locations (pages) used in this application
  sealed trait Loc

  case object DashboardLoc extends Loc

  case object FlightsLoc extends Loc

  case object UserDeskRecommendationsLoc extends Loc

  case class TerminalUserDeskRecommendationsLoc(terminalName: TerminalName) extends Loc


  val hasWl: ModelR[RootModel, Pot[Workloads]] = SPACircuit.zoom(_.workload)
  hasWl.value match {
    case Empty =>
      SPACircuit.dispatch(GetWorkloads("", "", airportShortCode))
    case default =>
      log.info(s"was $default")
  }


  import scala.scalajs.js.timers._
  import scala.concurrent.duration._
  import scala.concurrent.duration.FiniteDuration

  setInterval(FiniteDuration(30L, SECONDS)) {
    SPACircuit.dispatch(RequestFlights(0, 0))
  }
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
      renderR(ctl => {
        val airportWrapper = SPACircuit.connect(_.airportInfos)
        val flightsWrapper = SPACircuit.connect(m => m.flights)
        airportWrapper(airportInfoProxy => flightsWrapper(proxy => FlightsView(Props(proxy.value, airportInfoProxy.value))))
      })


    val terminalUserDeskRecs = terminalNames.map(tn => {
      staticRoute(s"#${tn}/userdeskrecs", TerminalUserDeskRecommendationsLoc(tn)) ~> renderR(ctl => {
        log.info(s"routing to ${tn} userdeskrecs")
        TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(tn)
      })
    })

    val userDeskRecsRoute = staticRoute("#userdeskrecs", UserDeskRecommendationsLoc) ~> renderR(ctl => {
      //todo take the queuenames from the workloads response
      log.info("running our user desk recs route")
      val queueUserDeskRecProps: Seq[QueueUserDeskRecsComponent.Props] = terminalNames.flatMap { terminalName =>
        queues.map { queueName =>
          val labelsPotRCP = SPACircuit.connect(_.workload.map(_.labels))
          val crunchResultPotRCP = SPACircuit.connect(_.queueCrunchResults.getOrElse(terminalName, Map()).getOrElse(queueName, Empty).flatMap(_._1))
          val userDeskRecsPotRCP = SPACircuit.connect(_.userDeskRec.getOrElse(terminalName, Map()).getOrElse(queueName, Empty))
          val flightsPotRCP = SPACircuit.connect(_.flights)
          val simulationResultPotRCP = SPACircuit.connect(_.simulationResult.getOrElse(terminalName, Map()).getOrElse(queueName, Empty))
          val userDeskRecsRowsPotRCP = makeUserDeskRecRowsPotRCP(terminalName, queueName)
          val airportInfoPotsRCP = SPACircuit.connect(_.airportInfos)
          QueueUserDeskRecsComponent.Props(terminalName,
            queueName,
            userDeskRecsRowsPotRCP,
            airportInfoPotsRCP,
            labelsPotRCP,
            crunchResultPotRCP,
            userDeskRecsPotRCP, flightsPotRCP, simulationResultPotRCP)
        }
      }

      <.div(
        ^.key := "UserDeskRecsWrapper",
        queueUserDeskRecProps.map(QueueUserDeskRecsComponent.component(_)))
    })

    val rule = terminalUserDeskRecs.foldLeft((dashboardRoute | flightsRoute | userDeskRecsRoute))((rules, terminalRule) => rules | terminalRule)
    rule.notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
  }.renderWith(layout)

  def makeUserDeskRecRowsPotRCP(terminalName: TerminalName, queueName: QueueName): ReactConnectProxy[Pot[List[UserDeskRecsRow]]] = {
    val userDeskRecRowsPotRCP = SPACircuit.connect(model => {
      val potRows: Pot[List[List[Any]]] = for {
        times <- model.workload.map(_.timeStamps)
        qcr: (Pot[CrunchResult], Pot[UserDeskRecs]) <- model.queueCrunchResults.getOrElse(terminalName, Map()).getOrElse(queueName, Empty)
        qur: UserDeskRecs <- model.userDeskRec.getOrElse(terminalName, Map()).getOrElse(queueName, Empty)
        simres: SimulationResult <- model.simulationResult.getOrElse(terminalName, Map()).getOrElse(queueName, Ready(SimulationResult(qcr._1.get.recommendedDesks.map(rd => DeskRec(0, rd)), qcr._1.get.waitTimes)))
        potcr: Pot[CrunchResult] = qcr._1
        potdr: Pot[UserDeskRecs] = qcr._2
        cr: CrunchResult <- potcr
        dr: UserDeskRecs <- potdr
      } yield {
        val aDaysWorthOfTimes: Seq[Long] = DeskRecsChart.takeEvery15th(times).take(96)
        val every15thRecDesk: Seq[Int] = DeskRecsChart.takeEvery15th(cr.recommendedDesks)
        val every15thCrunchWaitTime: Iterator[Int] = cr.waitTimes.grouped(15).map(_.max)
        val every15thSimWaitTime: Iterator[Int] = simres.waitTimes.grouped(15).map(_.max)
        val allRows = (aDaysWorthOfTimes :: every15thRecDesk :: qur.items :: every15thCrunchWaitTime :: every15thSimWaitTime :: Nil).transpose
        allRows
      }
      val is: Pot[List[UserDeskRecsRow]] = for (rows <- potRows) yield {
        rows.map(row => row match {
          case (time: Long) :: (crunchDeskRec: Int) :: (userDeskRec: DeskRecTimeslot) :: (waitTimeWithUserDeskRec: Int) :: (waitTimeWithCrunchDeskRec: Int) :: Nil =>
            UserDeskRecsRow(time, crunchDeskRec, userDeskRec, waitTimeWithUserDeskRec, waitTimeWithCrunchDeskRec)
          case default =>
            log.error(s"match error $default")
            throw new Exception(s"fail on $default")
        })
      }
      is
    })
    userDeskRecRowsPotRCP
  }


  // base layout for all pages
  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = {
    <.div(
      // here we use plain Bootstrap class names as these are specific to the top level layout defined here
      <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
        <.div(^.className := "container",
          <.div(^.className := "navbar-header", <.span(^.className := "navbar-brand", "DRT EDI Live Spike")),
          <.div(^.className := "collapse navbar-collapse", MainMenu(c, r.page, terminalNames)))),
      // currently active module is shown in this container
      <.div(^.className := "container", r.render()))
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
