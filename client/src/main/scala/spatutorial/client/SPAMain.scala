package drt.client

import chandu0101.scalajs.react.components.ReactTable
import diode.data.{Pot, Ready}
import japgolly.scalajs.react.ReactDOM
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom
import drt.client.components.{GlobalStyles, Layout, Staffing, TerminalDeploymentsPage}
import drt.client.logger._
import drt.client.modules.{FlightsView, FlightsWithSplitsView}
import drt.client.actions.Actions._
import drt.client.components.TerminalDeploymentsTable.{QueueDeploymentsRow, TerminalDeploymentsRow}
import drt.client.services.{DeskRecTimeslot, RequestFlights, SPACircuit}
import drt.client.services.HandyStuff.{PotCrunchResult, QueueStaffDeployments}
import drt.client.services.RootModel.QueueCrunchResults
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.Queues.QueueType
import drt.shared._

import scala.collection.immutable.{Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scalacss.Defaults._

object TableViewUtils {

  val eeadesk: QueueName = "eeaDesk"
  val noneeadesk: QueueName = "nonEeaDesk"
  val fasttrack: QueueName = "fastTrack"
  val egate: QueueName = "eGate"

  /**
    * Fixme: remove this line once we've removed the old terminal page
    */
  def queueNameMappingOrder = eeadesk :: noneeadesk :: egate :: Nil

  def queueDisplayName = Map(eeadesk -> "EEA", noneeadesk -> "Non-EEA", egate -> "e-Gates", fasttrack -> "Fast Track")

  def terminalDeploymentsRows(
                               terminalName: TerminalName,
                               airportConfigPot: Pot[AirportConfig],
                               timestamps: Seq[Long],
                               paxload: Map[QueueType, List[Double]],
                               queueCrunchResultsForTerminal: Map[QueueType, Pot[PotCrunchResult]],
                               simulationResult: Map[QueueType, Pot[SimulationResult]],
                               userDeskRec: QueueStaffDeployments
                             ): List[TerminalDeploymentsRow] = {
    airportConfigPot match {
      case Ready(airportConfig) =>
        log.info(s"call terminalUserDeskRecsRows")
        val queueRows: List[List[((Long, QueueType), QueueDeploymentsRow)]] = airportConfig.queues(terminalName).map((queueType: QueueType) => {
          simulationResult.get(queueType) match {
            case Some(Ready(sr)) =>
              val result = queueNosFromSimulationResult(timestamps, paxload, queueCrunchResultsForTerminal, userDeskRec, simulationResult, queueType)
              log.info(s"before transpose it is ${result}")
              log.info(s"before transpose it is ${result.map(_.length)}")
              queueDeploymentsRowsFromNos(queueType, result)
            case None =>
              queueCrunchResultsForTerminal.get(queueType) match {
                case Some(Ready(cr)) =>
                  queueDeploymentsRowsFromNos(queueType, queueNosFromCrunchResult(timestamps, paxload, queueCrunchResultsForTerminal, userDeskRec, queueType))
                case _ =>
                  List()
              }
          }
        }).toList

        val queueRowsByTime = queueRows.flatten.groupBy(tqr => tqr._1._1)

        queueRowsByTime.map((queueRows: (Long, List[((Long, QueueType), QueueDeploymentsRow)])) => {
          val qr = queueRows._2.map(_._2)
          TerminalDeploymentsRow(queueRows._1, qr)
        }).toList.sortWith(_.time < _.time)
      case _ => List()
    }
  }

  def queueDeploymentsRowsFromNos(qn: QueueType, queueNos: Seq[List[Long]]): List[((Long, QueueType), QueueDeploymentsRow)] = {
    queueNos.toList.transpose.zipWithIndex.map {
      case ((timestamp :: pax :: _ :: crunchDeskRec :: userDeskRec :: waitTimeCrunch :: waitTimeUser :: Nil), rowIndex) =>
        (timestamp, qn) -> QueueDeploymentsRow(
          timestamp = timestamp,
          pax = pax.toDouble,
          crunchDeskRec = crunchDeskRec.toInt,
          userDeskRec = DeskRecTimeslot(timestamp, userDeskRec.toInt),
          waitTimeWithCrunchDeskRec = waitTimeCrunch.toInt,
          waitTimeWithUserDeskRec = waitTimeUser.toInt,
          qn
        )
    }
  }

  private val numberOf15MinuteSlots = 96

  def queueNosFromSimulationResult(timestamps: Seq[Long], paxload: Map[QueueType, List[Double]],
                                   queueCrunchResultsForTerminal: QueueCrunchResults,
                                   userDeskRec: QueueStaffDeployments,
                                   simulationResult: Map[QueueType, Pot[SimulationResult]], queue: QueueType
                                  ): Seq[List[Long]] = {
    val ts = takeEveryNth(15)(timestamps).take(numberOf15MinuteSlots).toList

    log.info(s"queueNosFromSimulationResult queueCrunch ${queueCrunchResultsForTerminal}")
    log.info(s"queueNosFromSimulationResult userDeskRec ${userDeskRec}")
    Seq(
      ts,
      paxload(queue).grouped(15).map(paxes => paxes.sum.toLong).toList,
      simulationResult(queue).get.recommendedDesks.map(rec => rec.time).grouped(15).map(_.min).toList,
      queueCrunchResultsForTerminal(queue).get.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      getSafeUserDeskRecs(userDeskRec, queue, ts),
      queueCrunchResultsForTerminal(queue).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      simulationResult(queue).get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    )
  }


  def queueNosFromCrunchResult(timestamps: Seq[Long], paxload: Map[QueueType, List[Double]],
                               queueCrunchResultsForTerminal: QueueCrunchResults,
                               userDeskRec: QueueStaffDeployments, queue: QueueType
                              ): Seq[List[Long]] = {
    val ts = takeEveryNth(15)(timestamps).take(numberOf15MinuteSlots).toList
    val userDeskRecsSample: List[Long] = getSafeUserDeskRecs(userDeskRec, queue, ts)

    Seq(
      takeEveryNth(15)(timestamps).take(numberOf15MinuteSlots).toList,
      paxload(queue).grouped(15).map(paxes => paxes.sum.toLong).toList,
      List.range(0, queueCrunchResultsForTerminal(queue).get.get.recommendedDesks.length, 15).map(_.toLong),
      queueCrunchResultsForTerminal(queue).get.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      userDeskRecsSample,
      queueCrunchResultsForTerminal(queue).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(queue).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    )
  }

  def getSafeUserDeskRecs(userDeskRec: QueueStaffDeployments, queue: QueueType, ts: List[Long]) = {
    val queueUserDeskRecs = userDeskRec.get(queue)
    val userDeskRecsSample = queueUserDeskRecs match {
      case Some(Ready(udr)) => udr.items.map(_.deskRec.toLong).toList
      case _ => List.fill(ts.length)(0L)
    }
    userDeskRecsSample
  }

  def takeEveryNth[N](n: Int)(desks: Seq[N]) = desks.zipWithIndex.collect {
    case (v, i) if (i % n == 0) => v
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

  case class TerminalRecsLoc(id: String) extends Loc

  case class TerminalDepsLoc(id: String) extends Loc

  case object StaffingLoc extends Loc

  val initActions = Seq(
    GetWorkloads("", ""),
    GetAirportConfig(),
    RequestFlights(0, 0),
    GetShifts(),
    GetStaffMovements()
  )

  initActions.foreach(SPACircuit.dispatch(_))

  // configure the router
  val routerConfig = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._

    val renderFlights = renderR(ctl => {
      val airportWrapper = SPACircuit.connect(_.airportInfos)
      val flightsWrapper = SPACircuit.connect(m => m.flightsWithApiSplits)
      airportWrapper(airportInfoProxy => flightsWrapper(proxy => FlightsWithSplitsView(FlightsWithSplitsView.Props(proxy.value, airportInfoProxy.value))))
    })

    val rootRoute = staticRoute(root, FlightsLoc) ~> renderFlights

    val flightsRoute = staticRoute("#flights", FlightsLoc) ~> renderFlights

    val terminalDeps = dynamicRouteCT("#terminal-deps" / string("[a-zA-Z0-9]+")
      .caseClass[TerminalDepsLoc]) ~> dynRenderR((page: TerminalDepsLoc, ctl) => TerminalDeploymentsPage(page.id, ctl))

    val staffing = staticRoute("#staffing", StaffingLoc) ~>
      renderR(ctl => {
        Staffing()
      })

    val rule = rootRoute | flightsRoute | terminalDeps | staffing
    rule.notFound(redirectToPage(DashboardLoc)(Redirect.Replace))
  }.renderWith(layout)

  // base layout for all pages
  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = {
    Layout(c, r)
  }

  def pathToThisApp: String = dom.document.location.pathname

  @JSExport
  def main(): Unit = {
    //    Perf.start()
    //    scala.scalajs.js.Dynamic.global.window.Perf = Perf;
    log.warn("Application starting")
    // send log messages also to the server
    log.enableServerLogging(pathToThisApp + "/logging")
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
