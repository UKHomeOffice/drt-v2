package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.FlightsApi.TerminalName
import drt.shared.{SDateLike, _}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{getLocalLastMidnight, movementsUpdateCriteria, purgeExpired}

class StaffGraphStage(name: String = "",
                      initialShifts: ShiftAssignments,
                      initialFixedPoints: FixedPointAssignments,
                      optionalInitialMovements: Option[Seq[StaffMovement]],
                      now: () => SDateLike,
                      expireAfterMillis: MillisSinceEpoch,
                      airportConfig: AirportConfig,
                      numberOfDays: Int) extends GraphStage[FanInShape3[ShiftAssignments, FixedPointAssignments, Seq[StaffMovement], StaffMinutes]] {
  val inShifts: Inlet[ShiftAssignments] = Inlet[ShiftAssignments]("Shifts.in")
  val inFixedPoints: Inlet[FixedPointAssignments] = Inlet[FixedPointAssignments]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")

  override def shape: FanInShape3[ShiftAssignments, FixedPointAssignments, Seq[StaffMovement], StaffMinutes] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var shifts: ShiftAssignments = ShiftAssignments.empty
    var fixedPoints: FixedPointAssignments = FixedPointAssignments.empty
    var movementsOption: Option[Seq[StaffMovement]] = None
    var staffMinutes: Map[TM, StaffMinute] = Map()
    var staffMinuteUpdates: Map[TM, StaffMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    val expireBefore: MillisSinceEpoch = now().millisSinceEpoch - expireAfterMillis

    def maybeStaffSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(expireBefore, shifts, fixedPoints, movementsOption)

    override def preStart(): Unit = {
      shifts = initialShifts
      fixedPoints = initialFixedPoints
      movementsOption = optionalInitialMovements

      super.preStart()
    }

    setHandler(inShifts, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingShifts = grab(inShifts)
        log.info(s"Grabbed available inShifts")
        val updateCriteria = shiftsUpdateCriteria(shifts, incomingShifts)
        shifts = incomingShifts
        staffMinuteUpdates = updatesFromSources(maybeStaffSources, updateCriteria)
        tryPush()
        pull(inShifts)
        log.info(s"inShifts Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inFixedPoints, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingFixedPoints = grab(inFixedPoints)
        log.info(s"Grabbed available inFixedPoints")
        val updateCriteria = fixedPointsUpdateCriteria(fixedPoints, incomingFixedPoints)
        fixedPoints = incomingFixedPoints
        staffMinuteUpdates = updatesFromSources(maybeStaffSources, updateCriteria)
        tryPush()
        pull(inFixedPoints)
        log.info(s"inFixedPoints Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inMovements, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingMovements = grab(inMovements)
        log.info(s"Grabbed available inMovements")
        val existingMovements = movementsOption.map(_.toSet).getOrElse(Set())
        val updateCriteria: UpdateCriteria = movementsUpdateCriteria(existingMovements, incomingMovements)
        movementsOption = Option(incomingMovements)
        val latestUpdates = updatesFromSources(maybeStaffSources, updateCriteria)
        staffMinuteUpdates = mergeStaffMinuteUpdates(latestUpdates, staffMinuteUpdates)
        tryPush()
        pull(inMovements)
        log.info(s"inMovements Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
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
      val terminalNames = diff.map(_.terminalName)

      UpdateCriteria(minuteMillis, terminalNames)
    }

    def fixedPointsUpdateCriteria(oldFixedPoints: FixedPointAssignments, newFixedPoints: FixedPointAssignments): UpdateCriteria = {
      val fpMinutesToUpdate = allMinuteMillis(newFixedPoints) union allMinuteMillis(oldFixedPoints)
      val fpMinutesOfDayToUpdate = fpMinutesToUpdate.map(m => {
        val date = SDate(m)
        val hours = date.getHours()
        val minutes = date.getMinutes()
        hours * 60 + minutes
      })
      val firstMinute = getLocalLastMidnight(now())

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

      val terminalNames = ((newAssignments -- oldAssignments) union (oldAssignments -- newAssignments )).map(_.terminalName)

      UpdateCriteria(minuteMillis, terminalNames)
    }

    def mergeStaffMinuteUpdates(latestUpdates: Map[TM, StaffMinute], existingUpdates: Map[TM, StaffMinute]): Map[TM, StaffMinute] = latestUpdates
      .foldLeft(existingUpdates) {
        case (soFar, (id, updatedMinute)) => soFar.updated(id, updatedMinute)
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

    def updatesFromSources(maybeSources: Option[StaffSources], updateCriteria: UpdateCriteria): Map[TM, StaffMinute] = {
      val staff = maybeSources

      log.info(s"about to update ${updateCriteria.minuteMillis.size} staff minutes for ${updateCriteria.terminalNames}")

      implicit def mdToSd: MilliDate => SDateLike = (md: MilliDate) => SDate(md.millisSinceEpoch)

      val updatedMinutes = updateCriteria.minuteMillis
        .flatMap(m => {
          updateCriteria.terminalNames.map(tn => {
            val staffMinute = staff match {
              case None => StaffMinute(tn, m, 0, 0, 0)
              case Some(staffSources) =>
                val shifts = staffSources.shifts.terminalStaffAt(tn, SDate(m))
                val fixedPoints = staffSources.fixedPoints.terminalStaffAt(tn, SDate(m))
                val movements = staffSources.movements.terminalStaffAt(tn, m)
                StaffMinute(tn, m, shifts, fixedPoints, movements, lastUpdated = Option(SDate.now().millisSinceEpoch))
            }
            staffMinute
          })
        })

      val mergedMinutes = mergeMinutes(updatedMinutes, staffMinutes)

      staffMinutes = purgeExpired(mergedMinutes, (sm: StaffMinute) => sm.minute, now, expireAfterMillis)

      mergeMinutes(updatedMinutes, staffMinuteUpdates)
    }

    def mergeMinutes(updatedMinutes: Seq[StaffMinute], existingMinutes: Map[TM, StaffMinute]): Map[TM, StaffMinute] = {
      updatedMinutes.foldLeft(existingMinutes) {
        case (soFar, updatedMinute) =>
          soFar.get(updatedMinute.key) match {
            case Some(existingMinute) if existingMinute.equals(updatedMinute) => soFar
            case _ => soFar.updated(updatedMinute.key, updatedMinute)
          }
      }
    }

    def tryPush(): Unit = {
      if (isAvailable(outStaffMinutes)) {
        if (staffMinuteUpdates.nonEmpty) {
          log.info(s"Pushing ${staffMinuteUpdates.size} staff minute updates")
          push(outStaffMinutes, StaffMinutes(staffMinuteUpdates))
          staffMinuteUpdates = Map()
        }
      } else log.info(s"outStaffMinutes not available to push")
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

case class UpdateCriteria(minuteMillis: Seq[MillisSinceEpoch], terminalNames: Set[TerminalName])