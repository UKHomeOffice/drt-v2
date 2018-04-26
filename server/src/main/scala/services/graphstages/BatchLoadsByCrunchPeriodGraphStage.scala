package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch._

import scala.language.postfixOps


class BatchLoadsByCrunchPeriodGraphStage(now: () => SDateLike,
                                         expireAfterMillis: MillisSinceEpoch,
                                         crunchPeriodStartMillis: SDateLike => SDateLike
                                        ) extends GraphStage[FlowShape[Loads, Loads]] {
  val inLoads: Inlet[Loads] = Inlet[Loads]("Loads.in")
  val outLoads: Outlet[Loads] = Outlet[Loads]("Loads.out")

  override def shape: FlowShape[Loads, Loads] = new FlowShape(inLoads, outLoads)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutesQueue: List[(MillisSinceEpoch, Loads)] = List[(MillisSinceEpoch, Loads)]()

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingLoads = grab(inLoads)
        val updatedMinutes: List[(MillisSinceEpoch, Loads)] = mergeLoadsIntoQueue(incomingLoads, loadMinutesQueue, crunchPeriodStartMillis)

        loadMinutesQueue = Crunch.purgeExpired(updatedMinutes, now, expireAfterMillis)

        pushIfAvailable()

        pull(inLoads)
        log.info(s"inLoads Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.info(s"onPull called. ${loadMinutesQueue.length} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inLoads)) pull(inLoads)
        log.info(s"outLoads Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pushIfAvailable(): Unit = {
      loadMinutesQueue match {
        case Nil => log.info(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outLoads) =>
          log.info(s"outLoads not available to push")
        case (millis, loadMinutes) :: queueTail =>
          log.info(s"Pushing ${SDate(millis).toLocalDateTimeString()} ${loadMinutes.loadMinutes.size} load minutes for ${loadMinutes.loadMinutes.groupBy(_.terminalName).keys.mkString(", ")}")
          push(outLoads, loadMinutes)

          loadMinutesQueue = queueTail
          log.info(s"Queue length now ${loadMinutesQueue.length}")
      }
    }
  }
}
