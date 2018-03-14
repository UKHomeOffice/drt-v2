package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape3, Inlet, Outlet}
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{AirportConfig, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}

class StaffGraphStage(now: () => SDateLike, airportConfig: AirportConfig, numberOfDays: Int) extends GraphStage[FanInShape3[String, String, Seq[StaffMovement], Seq[StaffMinute]]] {
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[Seq[StaffMinute]] = Outlet[Seq[StaffMinute]]("StaffMinutes.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  var shiftsOption: Option[String] = None
  var fixedPointsOption: Option[String] = None
  var movementsOption: Option[Seq[StaffMovement]] = None
  var staffMinutes: Set[StaffMinute] = Set()
  var staffMinuteUpdates: Map[Int, StaffMinute] = Map()

  def maybeSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(shiftsOption, fixedPointsOption, movementsOption)

  override def shape: FanInShape3[String, String, Seq[StaffMovement], Seq[StaffMinute]] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      setHandler(inShifts, new InHandler {
        override def onPush(): Unit = {
            log.info(s"Grabbing available inShifts")
            shiftsOption = Option(grab(inShifts))
            staffMinuteUpdates = updatesFromSources(maybeSources)
            tryPush()
          pull(inShifts)
        }
      })

      setHandler(inFixedPoints, new InHandler {
        override def onPush(): Unit = {
            log.info(s"Grabbing available inFixedPoints")
            fixedPointsOption = Option(grab(inFixedPoints))
            staffMinuteUpdates = updatesFromSources(maybeSources)
            tryPush()
          pull(inFixedPoints)
        }
      })

      setHandler(inMovements, new InHandler {
        override def onPush(): Unit = {
            log.info(s"Grabbing available inMovements")
            movementsOption = Option(grab(inMovements))
            staffMinuteUpdates = updatesFromSources(maybeSources)
            tryPush()
          pull(inMovements)
        }
      })

      setHandler(outStaffMinutes, new OutHandler {

        override def onPull(): Unit = {
          tryPush()
          if (!hasBeenPulled(inShifts)) pull(inShifts)
          if (!hasBeenPulled(inFixedPoints)) pull(inFixedPoints)
          if (!hasBeenPulled(inMovements)) pull(inMovements)
        }
      })

      def updatesFromSources(maybeSources: Option[StaffSources]): Map[Int, StaffMinute] = {
        val staff = maybeSources
        val firstMinuteMillis = Crunch.getLocalLastMidnight(now()).millisSinceEpoch
        val lastMinuteMillis = firstMinuteMillis + (60000 * 60 * 24 * numberOfDays)

        val updatedMinutes: Set[StaffMinute] = (firstMinuteMillis until lastMinuteMillis by 60000)
          .flatMap(m => {
            airportConfig.terminalNames.map(tn => {
              val staffMinute = staff match {
                case None => StaffMinute(tn, m, 0, 0, 0)
                case Some(staffSources) =>
                  val shifts = staffSources.shifts.terminalStaffAt(tn, m)
                  val fixedPoints = staffSources.fixedPoints.terminalStaffAt(tn, m)
                  val movements = staffSources.movements.terminalStaffAt(tn, m)
                  StaffMinute(tn, m, shifts, fixedPoints, movements)
              }
              staffMinute
            })
          }).toSet

        (updatedMinutes -- staffMinutes).foldLeft(staffMinuteUpdates) {
          case (soFar, sm) => soFar.updated(sm.key, sm)
        }
      }

      def tryPush(): Unit = {
        if (isAvailable(outStaffMinutes) && staffMinuteUpdates.nonEmpty) {
          log.info(s"Pushing ${staffMinuteUpdates.size} staff minute updates")
          push(outStaffMinutes, staffMinuteUpdates)
          staffMinuteUpdates = Map()
        }
      }

    }
  }
}