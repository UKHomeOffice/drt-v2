package services.graphstages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinutes}
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{europeLondonTimeZone, getLocalLastMidnight, changedDays}

class StaffBatchUpdateGraphStage(now: () => SDateLike, expireAfterMillis: MillisSinceEpoch, offsetMinutes: Int) extends GraphStage[FlowShape[StaffMinutes, StaffMinutes]] {
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")

  override def shape: FlowShape[StaffMinutes, StaffMinutes] = new FlowShape(inStaffMinutes, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var staffMinutesQueue: List[(MillisSinceEpoch, StaffMinutes)] = List[(MillisSinceEpoch, StaffMinutes)]()

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingStaffMinutes = grab(inStaffMinutes)

        val daysToUpdate = changedDays(offsetMinutes, incomingStaffMinutes)
        log.info("daysToUpdate: " + daysToUpdate.keys.map(k => SDate(k).prettyDateTime()).mkString(", "))

        val updatedMinutes = daysToUpdate.foldLeft(staffMinutesQueue.toMap) {
          case (soFar, (dayMillis, staffMinutes)) => soFar.updated(dayMillis, StaffMinutes(staffMinutes))
        }.toList.sortBy(_._1)

        staffMinutesQueue = Crunch.purgeExpired(updatedMinutes, now, expireAfterMillis)

        pushIfAvailable()

        pull(inStaffMinutes)
        log.info(s"inStaffMinutes Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(outStaffMinutes, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.info(s"onPull called. ${staffMinutesQueue.length} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inStaffMinutes)) pull(inStaffMinutes)
        log.info(s"outStaffMinutes Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pushIfAvailable(): Unit = {
      staffMinutesQueue match {
        case Nil => log.info(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outStaffMinutes) =>
          log.info(s"outStaffMinutes not available to push")
        case (millis, staffMinutes) :: queueTail =>
          log.info(s"Pushing ${SDate(millis).toLocalDateTimeString()} ${staffMinutes.minutes.length} staff minutes for ${staffMinutes.minutes.groupBy(_.terminalName).keys.mkString(", ")}")
          push(outStaffMinutes, staffMinutes)

          staffMinutesQueue = queueTail
          log.info(s"Queue length now ${staffMinutesQueue.length}")
      }
    }
  }
}
