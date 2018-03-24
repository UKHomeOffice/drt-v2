package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape3, Inlet, Outlet}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.{AirportConfig, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.util.Success

class StaffGraphStage(name: String = "",
                      optionalInitialShifts: Option[String],
                      optionalInitialFixedPoints: Option[String],
                      optionalInitialMovements: Option[Seq[StaffMovement]],
                      now: () => SDateLike,
                      airportConfig: AirportConfig,
                      numberOfDays: Int) extends GraphStage[FanInShape3[String, String, Seq[StaffMovement], StaffMinutes]] {
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")

  var shiftsOption: Option[String] = None
  var fixedPointsOption: Option[String] = None
  var movementsOption: Option[Seq[StaffMovement]] = None
  var staffMinutes: Map[Int, StaffMinute] = Map()
  var staffMinuteUpdates: Map[Int, StaffMinute] = Map()

  def maybeStaffSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(shiftsOption, fixedPointsOption, movementsOption)

  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  override def shape: FanInShape3[String, String, Seq[StaffMovement], StaffMinutes] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    override def preStart(): Unit = {
      shiftsOption = optionalInitialShifts
      fixedPointsOption = optionalInitialFixedPoints
      movementsOption = optionalInitialMovements

      super.preStart()
    }

    setHandler(inShifts, new InHandler {
      override def onPush(): Unit = {
        shiftsOption = Option(grab(inShifts))
        log.info(s"Grabbed available inShifts: $shiftsOption")
        val minutesToUpdate = shiftsOption.map(allMinuteMillis).getOrElse(Set())
        staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
        tryPush()
        pull(inShifts)
      }
    })

    setHandler(inFixedPoints, new InHandler {
      override def onPush(): Unit = {
        fixedPointsOption = Option(grab(inFixedPoints))
        log.info(s"Grabbed available inFixedPoints: $fixedPointsOption")
        val minutesToUpdate = fixedPointMinutesToUpdate(fixedPointsOption)
        staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
        tryPush()
        pull(inFixedPoints)
      }
    })

    setHandler(inMovements, new InHandler {
      override def onPush(): Unit = {
        movementsOption = Option(grab(inMovements))
        log.info(s"Grabbed available inMovements: $movementsOption")
        val minutesToUpdate = movementsOption.map(allMinuteMillis).getOrElse(Set())
        staffMinuteUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
        tryPush()
        pull(inMovements)
      }
    })

    setHandler(outStaffMinutes, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"outStaffMinutes onPull called")
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
      if (isAvailable(outStaffMinutes)) {
        if (staffMinuteUpdates.nonEmpty) {
          log.info(s"Pushing ${staffMinuteUpdates.size} staff minute updates")
          push(outStaffMinutes, StaffMinutes(staffMinuteUpdates))
          staffMinuteUpdates = Map()
        }
      } else log.info(s"outStaffMinutes not available to push: $staffMinuteUpdates")
    }

  }

  def fixedPointMinutesToUpdate(maybeFixedPoints: Option[String]): Set[MillisSinceEpoch] = {
    val fpMinutesToUpdate = maybeFixedPoints.map(allMinuteMillis).getOrElse(Set())
    val fpMinutesOfDayToUpdate = fpMinutesToUpdate.map(m => {
      val date = SDate(m)
      val hours = date.getHours()
      val minutes = date.getMinutes()
      hours * 60 + minutes
    })
    log.info(s"fpMinutesOfDayToUpdate: $fpMinutesOfDayToUpdate from\n$maybeFixedPoints")
    val firstMinute = Crunch.getLocalLastMidnight(now())

    val set = (0 until numberOfDays)
      .flatMap(d =>
        fpMinutesOfDayToUpdate
          .toSeq
          .sortBy(identity)
          .map(m => {
            val date = firstMinute
              .addDays(d)
              .addMinutes(m)
            log.info(s"date: $date")
            date
              .millisSinceEpoch
          }
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
