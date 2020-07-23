package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, _}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.movementsUpdateCriteria
import services.metrics.{Metrics, StageTimer}

import scala.collection.immutable.SortedMap

class StaffGraphStage(initialShifts: ShiftAssignments,
                      initialFixedPoints: FixedPointAssignments,
                      optionalInitialMovements: Option[Seq[StaffMovement]],
                      now: () => SDateLike,
                      expireAfterMillis: Int,
                      numberOfDays: Int)
  extends GraphStage[FanInShape3[ShiftAssignments, FixedPointAssignments, Seq[StaffMovement], StaffMinutes]] {

  val inShifts: Inlet[ShiftAssignments] = Inlet[ShiftAssignments]("Shifts.in")
  val inFixedPoints: Inlet[FixedPointAssignments] = Inlet[FixedPointAssignments]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")
  val stageName = "staff"

  override def shape: FanInShape3[ShiftAssignments, FixedPointAssignments, Seq[StaffMovement], StaffMinutes] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var shifts: ShiftAssignments = ShiftAssignments.empty
    var fixedPoints: FixedPointAssignments = FixedPointAssignments.empty
    var movementsOption: Option[Seq[StaffMovement]] = None
    var staffMinuteUpdates: SortedMap[TM, StaffMinute] = SortedMap()

    val log: Logger = LoggerFactory.getLogger(getClass)

    val expireBefore: MillisSinceEpoch = now().millisSinceEpoch - expireAfterMillis

    def staffSources: StaffSources = Staffing.staffAvailableByTerminalAndQueue(expireBefore, shifts, fixedPoints, movementsOption)

    override def preStart(): Unit = {
      shifts = initialShifts
      fixedPoints = initialFixedPoints
      movementsOption = optionalInitialMovements

      super.preStart()
    }

    setHandler(inShifts, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inShifts)
        val incomingShifts = grab(inShifts)
        log.info(s"Grabbed available inShifts")
        val updateCriteria = shiftsUpdateCriteria(shifts, incomingShifts)
        shifts = incomingShifts
        applyUpdatesFromSources(staffSources, updateCriteria)
        tryPush()
        pull(inShifts)
        timer.stopAndReport()
      }
    })

    setHandler(inFixedPoints, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inFixedPoints)
        val incomingFixedPoints = grab(inFixedPoints)
        log.info(s"Grabbed available inFixedPoints")

        val updateCriteria = fixedPointsUpdateCriteria(fixedPoints, incomingFixedPoints)
        fixedPoints = incomingFixedPoints
        applyUpdatesFromSources(staffSources, updateCriteria)
        tryPush()
        pull(inFixedPoints)
        timer.stopAndReport()
      }
    })

    setHandler(inMovements, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inMovements)
        val incomingMovements = grab(inMovements)
        log.info(s"Grabbed available inMovements")
        val existingMovements = movementsOption.map(_.toSet).getOrElse(Set())
        val updateCriteria: UpdateCriteria = movementsUpdateCriteria(existingMovements, incomingMovements)
        movementsOption = Option(incomingMovements)
        applyUpdatesFromSources(staffSources, updateCriteria)
        tryPush()
        pull(inMovements)
        timer.stopAndReport()
      }
    })

    def shiftsUpdateCriteria(oldShifts: ShiftAssignments, newShifts: ShiftAssignments): UpdateCriteria = {
      val oldAssignments = oldShifts.assignments.toSet
      val newAssignments = newShifts.assignments.toSet

      val diff = newAssignments -- oldAssignments

      val minuteMillis = diff.flatMap(a => {
        val startMillis = a.startDt.millisSinceEpoch
        val endMillis = a.endDt.millisSinceEpoch
        startMillis to endMillis by 60000
      }).toSeq
      val terminalNames = diff.map(_.terminal)

      UpdateCriteria(minuteMillis, terminalNames)
    }

    def fixedPointsUpdateCriteria(oldFixedPoints: FixedPointAssignments, newFixedPoints: FixedPointAssignments): UpdateCriteria = {
      val fpMinutesToUpdate = allMinuteMillis(newFixedPoints) union allMinuteMillis(oldFixedPoints)
      val fpMinutesOfDayToUpdate = fpMinutesToUpdate.map(m => {
        val date = SDate(m, Crunch.europeLondonTimeZone)
        val hours = date.getHours()
        val minutes = date.getMinutes()
        hours * 60 + minutes
      })
      val firstMinute = now().getLocalLastMidnight

      val minuteMillis = (0 until numberOfDays)
        .flatMap(d =>
          fpMinutesOfDayToUpdate
            .toSeq
            .sortBy(identity)
            .map(m => {
              val date = firstMinute
                .addDays(d)
                .addMinutes(m)
              date.millisSinceEpoch
            })
        )

      val oldAssignments = oldFixedPoints.assignments.toSet
      val newAssignments = newFixedPoints.assignments.toSet
      val terminalNames = ((newAssignments -- oldAssignments) union (oldAssignments -- newAssignments)).map(_.terminal)

      UpdateCriteria(minuteMillis, terminalNames)
    }

    setHandler(outStaffMinutes, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.info(s"outStaffMinutes onPull called")
        tryPush()
        if (!hasBeenPulled(inShifts)) pull(inShifts)
        if (!hasBeenPulled(inFixedPoints)) pull(inFixedPoints)
        if (!hasBeenPulled(inMovements)) pull(inMovements)
        log.info(s"outStaffMinutes Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def applyUpdatesFromSources(staff: StaffSources, updateCriteria: UpdateCriteria): Unit = {
      log.info(s"about to update ${updateCriteria.minuteMillis.size} staff minutes for ${updateCriteria.terminalNames}")

      import SDate.implicits.sdateFromMilliDateLocal

      val updatedMinutes = SortedMap[TM, StaffMinute]() ++ updateCriteria.minuteMillis
        .flatMap(m => {
          updateCriteria.terminalNames.map { tn =>
            val shifts = staff.shifts.terminalStaffAt(tn, SDate(m))
            val fixedPoints = staff.fixedPoints.terminalStaffAt(tn, SDate(m, Crunch.europeLondonTimeZone))
            val movements = staff.movements.terminalStaffAt(tn, m)
            (TM(tn, m), StaffMinute(tn, m, shifts, fixedPoints, movements, lastUpdated = Option(SDate.now().millisSinceEpoch)))
          }
        })

      staffMinuteUpdates = staffMinuteUpdates ++ updatedMinutes
    }

    def tryPush(): Unit = {
      if (isAvailable(outStaffMinutes)) {
        if (staffMinuteUpdates.nonEmpty) {
          log.info(s"Pushing ${staffMinuteUpdates.size} staff minute updates")
          Metrics.counter(s"$stageName.minute-updates", staffMinuteUpdates.size)
          push(outStaffMinutes, StaffMinutes(staffMinuteUpdates))
          staffMinuteUpdates = SortedMap()
        }
      } else log.debug(s"outStaffMinutes not available to push")
    }
  }

  def allMinuteMillis(fixedPoints: FixedPointAssignments): Set[MillisSinceEpoch] = {
    fixedPoints.assignments
      .flatMap(a => {
        val startMillis = a.startDt.millisSinceEpoch
        val endMillis = a.endDt.millisSinceEpoch
        startMillis to endMillis by 60000
      })
      .toSet
  }
}

case class UpdateCriteria(minuteMillis: Seq[MillisSinceEpoch], terminalNames: Set[Terminal])
