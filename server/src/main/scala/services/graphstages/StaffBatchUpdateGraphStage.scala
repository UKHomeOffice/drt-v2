package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import drt.shared.CrunchApi.StaffMinutes
import drt.shared.{MilliDate, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.changedDays
import services.metrics.{Metrics, StageTimer}

import scala.collection.mutable

class StaffBatchUpdateGraphStage(now: () => SDateLike, expireAfterMillis: Int, offsetMinutes: Int) extends GraphStage[FlowShape[StaffMinutes, StaffMinutes]] {
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")
  val stageName = "batch-staff"

  override def shape: FlowShape[StaffMinutes, StaffMinutes] = new FlowShape(inStaffMinutes, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val staffMinutesQueue: mutable.SortedMap[MilliDate, StaffMinutes] = mutable.SortedMap()

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inStaffMinutes)
        val incomingStaffMinutes = grab(inStaffMinutes)

        val daysToUpdate = changedDays(offsetMinutes, incomingStaffMinutes)
        log.info("daysToUpdate: " + daysToUpdate.keys.toSeq.sorted.map(k => SDate(k).prettyDateTime()).mkString(", "))

        daysToUpdate.foreach {
          case (dayMillis, staffMinutes) => staffMinutesQueue += (MilliDate(dayMillis) -> StaffMinutes(staffMinutes))
        }

        Crunch.purgeExpired(staffMinutesQueue, MilliDate.atTime, now, expireAfterMillis.toInt)

        pushIfAvailable()

        pull(inStaffMinutes)
        timer.stopAndReport()
      }
    })

    setHandler(outStaffMinutes, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outStaffMinutes)
        log.info(s"onPull called. ${staffMinutesQueue.size} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inStaffMinutes)) pull(inStaffMinutes)
        timer.stopAndReport()
      }
    })

    def pushIfAvailable(): Unit = {
      staffMinutesQueue match {
        case empty if empty.isEmpty => log.debug(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outStaffMinutes) =>
          log.debug(s"outStaffMinutes not available to push")
        case minutes =>
          val (millis, staffMinutes) = minutes.head
          Metrics.counter(s"$stageName.minute-updates", staffMinutes.minutes.length)
          push(outStaffMinutes, staffMinutes)

          staffMinutesQueue -= millis
          log.info(s"Simulations queue length: ${staffMinutesQueue.size} days")
      }
    }
  }
}
