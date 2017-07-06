package drt.client

import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.components.TerminalDeploymentsTable.{QueueDeploymentsRow, QueueDeploymentsRowEntry, QueuePaxRowEntry, TerminalDeploymentsRow}
import drt.client.components.{GlobalStyles, Layout, Staffing, TerminalPage, TerminalsDashboardPage}
import drt.client.logger._
import drt.client.services.HandyStuff.{PotCrunchResult, QueueStaffDeployments}
import drt.client.services.RootModel.QueueCrunchResults
import drt.client.services.{DeskRecTimeslot, RequestFlights, SPACircuit}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.WebpackRequire
import japgolly.scalajs.react.extra.router._
import org.scalajs.dom

import scala.collection.immutable.{Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSImport}
import scalacss.Defaults._

object TableViewUtils {


  /**
    * Fixme: remove this line once we've removed the old terminal page
    */
  def queueNameMappingOrder = Queues.EeaDesk :: Queues.NonEeaDesk :: Queues.EGate :: Nil

  val queueDisplayNames = Map(Queues.EeaDesk -> "EEA", Queues.NonEeaDesk -> "Non-EEA", Queues.EGate -> "e-Gates",
    Queues.FastTrack -> "Fast Track",
    Queues.Transfer -> "Tx")

  def queueDisplayName(name: String) = queueDisplayNames.getOrElse(name, name)

  def terminalDeploymentsRows(
                               terminalName: TerminalName,
                               airportConfigPot: Pot[AirportConfig],
                               timestamps: Seq[Long],
                               paxload: Map[String, List[Double]],
                               queueCrunchResultsForTerminal: Map[QueueName, Pot[PotCrunchResult]],
                               simulationResult: Map[QueueName, Pot[SimulationResult]],
                               userDeskRec: QueueStaffDeployments,
                               actualDeskStats: Map[QueueName, Map[Long, DeskStat]]
                             ): List[TerminalDeploymentsRow] = {
    airportConfigPot match {
      case Ready(airportConfig) =>
        val queueRows: List[List[((Long, QueueName), QueueDeploymentsRow)]] = airportConfig.queues(terminalName).map {
          case Queues.Transfer => transferPaxRowsPerMinute(timestamps, paxload)
          case queueName =>
            val rows: List[((Long, String), QueueDeploymentsRowEntry)] = queueDeploymentRowsPerMinute(timestamps, paxload, queueCrunchResultsForTerminal, simulationResult, userDeskRec, queueName)
            DeskStats.withActuals(rows.map(_._2), actualDeskStats).map(qdre => ((qdre.timestamp, qdre.queueName), qdre))
        }.toList

        val queueRowsByTime = queueRows.flatten.groupBy(tqr => tqr._1._1)

        queueRowsByTime.map((queueRows: (Long, List[((Long, QueueName), QueueDeploymentsRow)])) => {
          val qr = queueRows._2.map(_._2)
          TerminalDeploymentsRow(queueRows._1, qr)
        }).toList.sortWith(_.time < _.time)
      case _ => List()
    }
  }

  def transferPaxRowsPerMinute(timestamps: Seq[Long], queuePaxload: Map[String, List[Double]]): List[((Long, QueueName), QueueDeploymentsRow)] = {
    val sampledTs = sampleTimestampsForRows(timestamps)
    val sampledPaxload = samplePaxLoad(queuePaxload, Queues.Transfer)
    val zippedTsAndPaxload = sampledTs.zip(sampledPaxload)
    val res = zippedTsAndPaxload.map {
      case (ts, paxLoad) => (ts, Queues.Transfer) -> QueuePaxRowEntry(ts, Queues.Transfer, paxLoad)
    }
    res
  }

  def queueDeploymentRowsPerMinute(timestamps: Seq[Long],
                                   paxload: Map[String, List[Double]],
                                   queueCrunchResultsForTerminal: Map[QueueName, Pot[PotCrunchResult]],
                                   simulationResult: Map[QueueName, Pot[SimulationResult]],
                                   userDeskRec: QueueStaffDeployments,
                                   queueName: QueueName): List[((Long, String), QueueDeploymentsRowEntry)] = {
    simulationResult.get(queueName) match {
      case Some(Ready(sr)) =>
        val result = queueNosFromSimulationResult(timestamps, paxload, queueCrunchResultsForTerminal, userDeskRec, simulationResult, queueName)
        queueDeploymentsRowsFromNos(queueName, result)
      case None =>
        queueCrunchResultsForTerminal.get(queueName) match {
          case Some(Ready(cr)) =>
            queueDeploymentsRowsFromNos(queueName, queueNosFromCrunchResult(timestamps, paxload, queueCrunchResultsForTerminal, userDeskRec, queueName))
          case _ =>
            List()
        }
    }
  }

  def queueDeploymentsRowsFromNos(queueName: QueueName, queueNos: Seq[List[Long]]): List[((Long, String), QueueDeploymentsRowEntry)] = {
    queueNos.toList.transpose.zipWithIndex.map {
      case ((timestamp :: pax :: _ :: crunchDeskRec :: userDeskRec :: waitTimeCrunch :: waitTimeUser :: Nil), rowIndex) =>
        (timestamp, queueName) -> QueueDeploymentsRowEntry(
          timestamp = timestamp,
          pax = pax.toDouble,
          crunchDeskRec = crunchDeskRec.toInt,
          userDeskRec = DeskRecTimeslot(timestamp, userDeskRec.toInt),
          waitTimeWithCrunchDeskRec = waitTimeCrunch.toInt,
          waitTimeWithUserDeskRec = waitTimeUser.toInt,
          queueName = queueName
        )
    }
  }

  private val numberOf15MinuteSlots = 96

  /**
    * Stitch these sequences of metrics over time into a seq of seq of metrics that will then be
    * converted into a QueueDeploymentsRow
    *
    * @param timestamps
    * @param paxload
    * @param queueCrunchResultsForTerminal
    * @param userDeskRec
    * @param simulationResult
    * @param qn
    * @return
    */
  def queueNosFromSimulationResult(timestamps: Seq[Long], paxload: Map[String, List[Double]],
                                   queueCrunchResultsForTerminal: QueueCrunchResults,
                                   userDeskRec: QueueStaffDeployments,
                                   simulationResult: Map[QueueName, Pot[SimulationResult]], qn: QueueName
                                  ): Seq[List[Long]] = {
    val ts = sampleTimestampsForRows(timestamps)

    log.info(s"queueNosFromSimulationResult queueCrunch ${queueCrunchResultsForTerminal}")
    log.info(s"queueNosFromSimulationResult userDeskRec ${userDeskRec}")
    val simulationResultWaitTimes = simulationResult(qn).get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    //simulationResults won't exist for some 'queues' (like transfer) so pad it out to the right length with 0s for now
    val paddedSimulationResultWaitTimes: List[Long] = padSimResult(simulationResultWaitTimes, numberOf15MinuteSlots)
    val simResultRecDesks = simulationResult(qn).get.recommendedDesks.map(rec => rec.time).grouped(15).map(_.min).toList
    val paddedRecDesks: List[Long] = padSimResult(simResultRecDesks, numberOf15MinuteSlots)

    Seq(
      ts,
      samplePaxLoad(paxload, qn),
      paddedRecDesks,
      queueCrunchResultsForTerminal(qn).get.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      getSafeUserDeskRecs(userDeskRec, qn, ts),
      queueCrunchResultsForTerminal(qn).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      paddedSimulationResultWaitTimes
    )
  }


  def samplePaxLoad(paxload: Map[String, List[Double]], qn: QueueName): List[Long] = {
    paxload.get(qn) match {
      case Some(qns) => qns.grouped(15).map(paxes => paxes.sum.toLong).toList
      case None => List.fill(numberOf15MinuteSlots)(0L)
    }
  }

  def sampleTimestampsForRows(timestamps: Seq[Long]) = {
    takeEveryNth(15)(timestamps).take(numberOf15MinuteSlots).toList
  }

  def padSimResult(simulationResultWaitTimes: List[Long], numberOf15MinuteSlots: Int): List[Long] = {
    if (simulationResultWaitTimes.isEmpty) List.fill(numberOf15MinuteSlots)(0) else simulationResultWaitTimes
  }

  def queueNosFromCrunchResult(timestamps: Seq[Long], paxload: Map[String, List[Double]],
                               queueCrunchResultsForTerminal: QueueCrunchResults,
                               userDeskRec: QueueStaffDeployments, qn: QueueName
                              ): Seq[List[Long]] = {
    val ts = sampleTimestampsForRows(timestamps)
    val userDeskRecsSample: List[Long] = getSafeUserDeskRecs(userDeskRec, qn, ts)

    Seq(
      ts,
      paxload(qn).grouped(15).map(paxes => paxes.sum.toLong).toList,
      List.range(0, queueCrunchResultsForTerminal(qn).get.get.recommendedDesks.length, 15).map(_.toLong),
      queueCrunchResultsForTerminal(qn).get.get.recommendedDesks.map(_.toLong).grouped(15).map(_.max).toList,
      userDeskRecsSample,
      queueCrunchResultsForTerminal(qn).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList,
      queueCrunchResultsForTerminal(qn).get.get.waitTimes.map(_.toLong).grouped(15).map(_.max).toList
    )
  }

  def getSafeUserDeskRecs(userDeskRec: QueueStaffDeployments, qn: QueueName, ts: List[Long]) = {
    val queueUserDeskRecs = userDeskRec.get(qn)
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

  case class TerminalsDashboardLoc(hours: Int) extends Loc

  case object StaffingLoc extends Loc

  val initActions = Seq(
    GetAirportConfig(),
    GetWorkloads("", ""),
    RequestFlights(0, 0),
    GetShifts(),
    GetFixedPoints(),
    GetStaffMovements(),
    GetActualDeskStats()
  )

  initActions.foreach(SPACircuit.dispatch(_))

  // configure the router
  val routerConfig = RouterConfigDsl[Loc].buildConfig { dsl =>
    import dsl._

    val renderStaffing = renderR(_ => Staffing())
    val home = staticRoute(root, StaffingLoc) ~> renderStaffing
    val staffing = staticRoute("#staffing", StaffingLoc) ~> renderStaffing
    val terminal = dynamicRouteCT("#terminal" / string("[a-zA-Z0-9]+")
      .caseClass[TerminalDepsLoc]) ~> dynRenderR((page: TerminalDepsLoc, ctl) => {
      TerminalPage(page.id, ctl)
    })
    val terminalsDashboard = dynamicRouteCT("#terminalsDashboard" / int
      .caseClass[TerminalsDashboardLoc]) ~> dynRenderR((page: TerminalsDashboardLoc, ctl) => {
      TerminalsDashboardPage(page.hours, ctl)
    })

    val rule = home | terminal | staffing | terminalsDashboard
    rule.notFound(redirectToPage(StaffingLoc)(Redirect.Replace))
  }.renderWith(layout)

  // base layout for all pages
  def layout(c: RouterCtl[Loc], r: Resolution[Loc]) = {
    Layout(c, r)
  }

  def pathToThisApp: String = dom.document.location.pathname

  def require(): Unit = {
    log.info(s"app main require()")
    WebpackRequire.React
    WebpackRequire.ReactDOM
    ()
  }

  @JSExport
  def main(): Unit = {
    require()

    //    Perf.start()
    //    scala.scalajs.js.Dynamic.global.window.Perf = Perf;
    log.warn("Application starting")
    // send log messages also to the server
    log.enableServerLogging(pathToThisApp + "/logging")
    log.info("This message goes to server as well")

    // create stylesheet
    import scalacss.ScalaCssReact._

    //    ReactTable.DefaultStyle.addToDocument()
    //    Spinner.Style.addToDocument()
    GlobalStyles.addToDocument()
    // create the router
    val router = Router(BaseUrl.until_#, routerConfig.logToConsole)
    // tell React to render the router in the document body
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

object DeskStats {
  def withActuals(queueRows: List[QueueDeploymentsRowEntry], actDeskNos: Map[QueueName, Map[Long, DeskStat]]) = {
    queueRows.map {
      case qdr: QueueDeploymentsRowEntry => {
        val timeToDesks: Map[Long, DeskStat] = actDeskNos.getOrElse(qdr.queueName, Map[Long, DeskStat]())
        val deskStat: DeskStat = timeToDesks.getOrElse(qdr.timestamp, DeskStat(Option.empty[Int], Option.empty[Int]))
        qdr.copy(actualDeskRec = deskStat.desks, actualWaitTime = deskStat.waitTime)
      }
    }
  }
}
