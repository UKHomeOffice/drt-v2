package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.Crunch.{CrunchMinute, CrunchState}
import drt.shared.{ActualDeskStats, DeskStat}
import org.slf4j.{Logger, LoggerFactory}

import scala.language.postfixOps

class ActualDesksAndWaitTimesGraphStage() extends GraphStage[FanInShape2[CrunchState, ActualDeskStats, CrunchState]] {
  val inCrunch: Inlet[CrunchState] = Inlet[CrunchState]("CrunchStateWithoutActualDesks.in")
  val inDeskStats: Inlet[ActualDeskStats] = Inlet[ActualDeskStats]("ActualDesks.in")
  val outCrunch: Outlet[CrunchState] = Outlet[CrunchState]("CrunchStateWithActualDesks.out")
  override val shape = new FanInShape2(inCrunch, inDeskStats, outCrunch)

  var crunchStateOption: Option[CrunchState] = None
  var actualDesksOption: Option[ActualDeskStats] = None
  var crunchStateWithActualDeskStats: Option[CrunchState] = None

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    import ActualDesksAndWaitTimesGraphStage._

    setHandler(inCrunch, new InHandler {
      override def onPush(): Unit = {
        grabBoth()
        crunchStateWithActualDeskStats = addActualsIfAvailable()
        pushAndPull()
      }
    })

    setHandler(inDeskStats, new InHandler {
      override def onPush(): Unit = {
        grabBoth()
        crunchStateWithActualDeskStats = addActualsIfAvailable()
        pushAndPull()
      }
    })

    setHandler(outCrunch, new OutHandler {
      override def onPull(): Unit = {
        pushAndPull()
      }
    })

    def pushAndPull() = {
      if (isAvailable(outCrunch)) {
        crunchStateWithActualDeskStats match {
          case Some(cs) =>
            log.info(s"Pushing out crunch")
            push(outCrunch, cs)
            crunchStateWithActualDeskStats = None
          case None =>
            log.info(s"Nothing to push")
        }
      }
      if (!hasBeenPulled(inDeskStats)) pull(inDeskStats)
      if (!hasBeenPulled(inCrunch)) pull(inCrunch)
    }

    def grabBoth() = {
      if (isAvailable(inDeskStats)) {
        log.info(s"Grabbing available inDeskStats")
        actualDesksOption = Option(grab(inDeskStats))
      }

      if (isAvailable(inCrunch)) {
        log.info(s"Grabbing available inCrunch")
        crunchStateOption = Option(grab(inCrunch))
      }
    }

    def addActualsIfAvailable() = (actualDesksOption, crunchStateOption) match {
      case (Some(ad), Some(cs)) =>
        log.info("Got actuals, adding to CrunchState")
        Option(addActualsToCrunchMinutes(ad, cs))
      case _ =>
        crunchStateOption
    }
  }
}

object ActualDesksAndWaitTimesGraphStage {
  val fifteenMins = 15 * 60000

  def addActualsToCrunchMinutes(act: ActualDeskStats, cs: CrunchState): CrunchState = {
    val crunchMinutesWithActuals = cs.crunchMinutes.map((cm: CrunchMinute) => {
      val deskStat: Option[DeskStat] = act.desks
        .get(cm.terminalName)
        .flatMap(_.get(cm.queueName)).flatMap(qds => {
        qds.find(ds => ds._1 <= cm.minute && ds._1 + fifteenMins > cm.minute).map {
          case (time, ds: DeskStat) => ds
        }
      })
      cm.copy(actDesks = deskStat.flatMap(_.desks), actWait = deskStat.flatMap(_.waitTime))
    })
    cs.copy(crunchMinutes = crunchMinutesWithActuals)
  }
}
