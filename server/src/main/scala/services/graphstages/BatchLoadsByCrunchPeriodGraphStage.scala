package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch._
import services.metrics.{Metrics, StageTimer}

import scala.collection.mutable

class BatchLoadsByCrunchPeriodGraphStage(now: () => SDateLike,
                                         expireAfterMillis: MillisSinceEpoch,
                                         crunchPeriodStartMillis: SDateLike => SDateLike
                                        ) extends GraphStage[FlowShape[Loads, Loads]] {
  val inLoads: Inlet[Loads] = Inlet[Loads]("Loads.in")
  val outLoads: Outlet[Loads] = Outlet[Loads]("Loads.out")
  val stageName = "batch-loads"

  override def shape: FlowShape[Loads, Loads] = new FlowShape(inLoads, outLoads)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val loadMinutesQueue: mutable.SortedMap[MilliDate, Loads] = mutable.SortedMap()

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inLoads)
        val incomingLoads = grab(inLoads)
        mergeLoadsIntoQueue(incomingLoads, loadMinutesQueue, crunchPeriodStartMillis)

        Crunch.purgeExpired(loadMinutesQueue, MilliDate.atTime, now, expireAfterMillis.toInt)

        pushIfAvailable()

        pull(inLoads)
        timer.stopAndReport()
      }
    })

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outLoads)
        log.info(s"onPull called. ${loadMinutesQueue.size} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inLoads)) pull(inLoads)
        timer.stopAndReport()
      }
    })

    def pushIfAvailable(): Unit = {
      loadMinutesQueue match {
        case emptyLoads if emptyLoads.isEmpty => log.debug(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outLoads) => log.debug(s"outLoads not available to push")
        case loads =>
          val (millis, loadMinutes) = loads.head
          val terminalNames = loadMinutes.loadMinutes.groupBy(_._1.terminalName).keys.mkString(", ")
          val loadMinutesCount = loadMinutes.loadMinutes.size
          log.info(s"Pushing ${SDate(millis).toLocalDateTimeString()} $loadMinutesCount load minutes for $terminalNames")
          Metrics.counter(s"$stageName.minute-updates", loadMinutes.loadMinutes.size)

          push(outLoads, loadMinutes)

          loadMinutesQueue -= millis
          log.info(s"Crunch queue length: ${loadMinutesQueue.size} days")
      }
    }
  }
}
