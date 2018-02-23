package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{CrunchMinute, PortState}
import drt.shared.{ActualDeskStats, DeskStat}
import org.slf4j.{Logger, LoggerFactory}

import scala.language.postfixOps

class ActualDesksAndWaitTimesGraphStage() extends GraphStage[FanInShape2[PortState, ActualDeskStats, PortState]] {
  val inCrunch: Inlet[PortState] = Inlet[PortState]("PortStateWithoutActualDesks.in")
  val inDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDesks.in")
  val outCrunch: Outlet[PortState] = Outlet[PortState]("PortStateWithActualDesks.out")

  val allInlets = List(inCrunch, inDeskStats)

  override val shape = new FanInShape2(inCrunch, inDeskStats, outCrunch)

  var portStateOption: Option[PortState] = None
  var actualDesksOption: Option[ActualDeskStats] = None
  var portStateWithActualDeskStats: Option[PortState] = None

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    import ActualDesksAndWaitTimesGraphStage._

    setHandler(inCrunch, new InHandler {
      override def onPush(): Unit = {
        grabBoth()
        portStateWithActualDeskStats = addActualsIfAvailable()
        pushIfAvailable()
        pullAll()
      }
    })

    setHandler(inDeskStats, new InHandler {
      override def onPush(): Unit = {
        grabBoth()
        portStateWithActualDeskStats = addActualsIfAvailable()
        pushIfAvailable()
        pullAll()
      }
    })

    setHandler(outCrunch, new OutHandler {
      override def onPull(): Unit = {
        pushIfAvailable()
        pullAll()
      }
    })

    def pushIfAvailable(): Unit = {
      if (isAvailable(outCrunch)) {
        portStateWithActualDeskStats match {
          case Some(ps) =>
            log.info(s"Pushing portStateWithActualDeskStats: ${ps.crunchMinutes.size} cms, ${ps.staffMinutes.size} sms, ${ps.flights.size} fts")
            push(outCrunch, ps)
            portStateWithActualDeskStats = None
          case None =>
            log.info(s"Nothing to push")
        }
      }
    }

    def pullAll(): Unit = {
      allInlets.foreach(inlet => if (!hasBeenPulled(inlet)) {
        log.info(s"${inlet.toString} has not been pulled so pulling now")
        pull(inlet)
      })
    }

    def grabBoth(): Unit = {
      if (isAvailable(inDeskStats)) {
        log.info(s"Grabbing available inDeskStats")
        actualDesksOption = Option(grab(inDeskStats))
      }

      if (isAvailable(inCrunch)) {
        log.info(s"Grabbing available inCrunch")
        portStateOption = Option(grab(inCrunch))
      }
    }

    def addActualsIfAvailable(): Option[PortState] = (actualDesksOption, portStateOption) match {
      case (None, None) =>
        log.info(s"No actual desks or port state. Can't merge")
        portStateOption
      case (Some(_), None) =>
        log.info(s"No actual port state. Can't merge")
        portStateOption
      case (None, Some(_)) =>
        log.info(s"No actual desks. Can't merge")
        portStateOption
      case (Some(ad), Some(cs)) =>
        log.info("Adding actuals to PortState")
        Option(addActualsToCrunchMinutes(ad, cs))
      case _ =>
        portStateOption
    }
  }
}

object ActualDesksAndWaitTimesGraphStage {
  val fifteenMins: Int = 15 * 60000

  def addActualsToCrunchMinutes(act: ActualDeskStats, ps: PortState): PortState = {
    val crunchMinutesWithActuals = ps.crunchMinutes.values.map((cm: CrunchMinute) => {
      val deskStat: Option[DeskStat] = act.desks
        .get(cm.terminalName)
        .flatMap(_.get(cm.queueName)).flatMap((qds: Map[Long, DeskStat]) => {
        qds.find(ds => ds._1 <= cm.minute && ds._1 + fifteenMins > cm.minute).map {
          case (time, ds: DeskStat) => ds
        }
      })
      (cm.key, cm.copy(actDesks = deskStat.flatMap(_.desks), actWait = deskStat.flatMap(_.waitTime)))
    }).toMap
    ps.copy(crunchMinutes = crunchMinutesWithActuals)
  }
}
