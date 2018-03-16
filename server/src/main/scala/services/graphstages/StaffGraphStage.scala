package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape3, Inlet, Outlet}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.{AirportConfig, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.Success

class StaffGraphStage(optionalInitialShifts: Option[String],
                      optionalInitialFixedPoints: Option[String],
                      optionalInitialMovements: Option[Seq[StaffMovement]],
                      now: () => SDateLike,
                      airportConfig: AirportConfig,
                      numberOfDays: Int) extends GraphStage[FanInShape3[String, String, Seq[StaffMovement], StaffMinutes]] {
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")

  val log: Logger = LoggerFactory.getLogger(getClass)

  var shiftsOption: Option[String] = None
  var fixedPointsOption: Option[String] = None
  var movementsOption: Option[Seq[StaffMovement]] = None
  var staffMinutes: Map[Int, StaffMinute] = Map()
  var staffMinuteUpdates: Map[Int, StaffMinute] = Map()

  def maybeStaffSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(shiftsOption, fixedPointsOption, movementsOption)

  override def shape: FanInShape3[String, String, Seq[StaffMovement], StaffMinutes] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {

      override def preStart(): Unit = {
        shiftsOption = optionalInitialShifts
        fixedPointsOption = optionalInitialFixedPoints
        movementsOption = optionalInitialMovements

        super.preStart()
      }

      setHandler(inShifts, new InHandler {
        override def onPush(): Unit = {
          log.info(s"Grabbing available inShifts")
          shiftsOption = Option(grab(inShifts))
          val minutesToUpdate = shiftsOption.map(allMinuteMillis).getOrElse(Set())
          staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
          tryPush()
          pull(inShifts)
        }
      })

      setHandler(inFixedPoints, new InHandler {
        override def onPush(): Unit = {
          log.info(s"Grabbing available inFixedPoints")
          fixedPointsOption = Option(grab(inFixedPoints))
          val minutesToUpdate = fixedPointMinutesToUpdate(fixedPointsOption)
          staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
          tryPush()
          pull(inFixedPoints)
        }
      })

      setHandler(inMovements, new InHandler {
        override def onPush(): Unit = {
          log.info(s"Grabbing available inMovements")
          movementsOption = Option(grab(inMovements))
          val minutesToUpdate = movementsOption.map(allMinuteMillis).getOrElse(Set())
          staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
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

      def updatesFromSources(maybeSources: Option[StaffSources], minutesToUpdate: Set[MillisSinceEpoch]): Map[Int, StaffMinute] = {
        val staff = maybeSources

        val updatedMinutes: Set[StaffMinute] = minutesToUpdate
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
          })

        staffMinutes = updatedMinutes.foldLeft(staffMinutes) {
          case (soFar, updatedMinute) => soFar.updated(updatedMinute.key, updatedMinute)
        }

        updatedMinutes.foldLeft(staffMinuteUpdates) {
          case (soFar, sm) => soFar.updated(sm.key, sm)
        }
      }

      def tryPush(): Unit = {
        if (isAvailable(outStaffMinutes) && staffMinuteUpdates.nonEmpty) {
          log.info(s"Pushing ${staffMinuteUpdates.size} staff minute updates")
          push(outStaffMinutes, StaffMinutes(staffMinuteUpdates))
          staffMinuteUpdates = Map()
        }
      }

    }
  }

  def fixedPointMinutesToUpdate(maybeFixedPoints: Option[String]): Set[MillisSinceEpoch] = {
    val fpMinutesToUpdate = maybeFixedPoints.map(allMinuteMillis).getOrElse(Set())
    val fpMinutesOfDayToUpdate = fpMinutesToUpdate.map(m => {
      val hours = SDate(m).getHours()
      val minutes = SDate(m).getMinutes()
      hours * 60 + minutes
    })
    val firstMinute = Crunch.getLocalLastMidnight(now())

    val set = (0 until numberOfDays)
      .flatMap(d =>
        fpMinutesOfDayToUpdate
          .map(m =>
            firstMinute
              .addDays(d)
              .addMinutes(m)
              .millisSinceEpoch
          )
      ).toSet
    set
  }

  def allMinuteMillis(rawAssignments: String): Set[MillisSinceEpoch] = {
    StaffAssignmentParser(rawAssignments)
      .parsedAssignments
      .toList
      .collect {
        case Success(assignment) => assignment
      }
      .flatMap(a => {
        val startMillis = a.startDt.millisSinceEpoch
        val endMillis = a.endDt.millisSinceEpoch
        startMillis to endMillis by 60000
      })
      .toSet
  }

  def allMinuteMillis(movements: Seq[StaffMovement]): Set[MillisSinceEpoch] = {
    movements
      .groupBy(_.uUID)
      .flatMap {
        case (_, mmPair) =>
          val startMillis = mmPair.map(_.time.millisSinceEpoch).min
          val endMillis = mmPair.map(_.time.millisSinceEpoch).max
          startMillis until endMillis by 60000
      }
      .toSet
  }
}
