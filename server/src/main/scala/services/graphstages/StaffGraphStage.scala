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
                      initialStaffMinutes: StaffMinutes,
                      now: () => SDateLike,
                      expireAfterMillis: MillisSinceEpoch,
                      airportConfig: AirportConfig,
                      numberOfDays: Int,
                      checkRequiredUpdatesOnStartup: Boolean) extends GraphStage[FanInShape3[ShiftAssignments, FixedPointAssignments, Seq[StaffMovement], StaffMinutes]] {
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

    def staffSources: StaffSources = Staffing.staffAvailableByTerminalAndQueue(expireBefore, shifts, fixedPoints, movementsOption)

    override def preStart(): Unit = {
      shifts = initialShifts
      fixedPoints = initialFixedPoints
      movementsOption = optionalInitialMovements
      staffMinutes = initialStaffMinutes.minutes.map(sm => (TM(sm.terminalName, sm.minute), sm)).toMap

      if (checkRequiredUpdatesOnStartup) {
        missingMinutes ++ minutesRequiringUpdate match {
          case m if m.isEmpty => log.info(s"No minutes to add or update")
          case minutes =>
            val updateCriteria = UpdateCriteria(minutes.toList.sorted, airportConfig.terminalNames.toSet)
            log.info(s"${updateCriteria.minuteMillis.length} minutes to add or update")
            staffMinuteUpdates = updatesFromSources(staffSources, updateCriteria)
            log.info(s"${staffMinuteUpdates.size} minutes generated across terminals")
        }
      } else {
        log.warn(s"Prestart update checks are disabled")
      }

      super.preStart()
    }

    def minutesRequiringUpdate: Set[MillisSinceEpoch] = {
      val midnight = getLocalLastMidnight(now()).millisSinceEpoch

      val minutesMillis = (midnight until (midnight + (numberOfDays.toLong * 60 * 60 * 24 * 1000)) by 60000).toArray

      log.info(s"Checking $numberOfDays days worth of minutes (${minutesMillis.length} minutes)")
      val maybeUpdates = for {
        terminal <- airportConfig.terminalNames
        minuteMillis <- minutesMillis
      } yield {
        val shifts = staffSources.shifts.terminalStaffAt(terminal, SDate(minuteMillis))
        lazy val fps = staffSources.fixedPoints.terminalStaffAt(terminal, SDate(minuteMillis))((md: MilliDate) => SDate(md))
        lazy val mms = staffSources.movements.terminalStaffAt(terminal, minuteMillis)
        val tm: TM = TM(terminal, minuteMillis)
        staffMinutes.get(tm) match {
          case None => Option(minuteMillis)
          case Some(StaffMinute(_, _, s, f, m, _)) if s != shifts || f != fps || m != mms => Option(minuteMillis)
          case _ => None
        }
      }

      val requiringUpdate = maybeUpdates.collect { case Some(millis) => millis }

      log.info(s"Finished checking. Found ${requiringUpdate.length} minutes requiring an update")

      requiringUpdate.toSet
    }

    def missingMinutes: Set[MillisSinceEpoch] = {
      val midnight = getLocalLastMidnight(now()).millisSinceEpoch

      val startMillis = SDate.now().millisSinceEpoch
      log.info(s"Checking for missing minutes")
      val minutesInADay = 1440
      val oneDayMillis = 60 * 60 * 24 * 1000
      val oneMinuteMillis = 60 * 1000

      val missing = (0 to numberOfDays).foldLeft(List[MillisSinceEpoch]()) {
        case (missingSoFar, day) =>
          val dayMillis = midnight + (day * oneDayMillis)
          if (!minuteExists(dayMillis, airportConfig.terminalNames.toList)) {
            (0 until minutesInADay).map(m => {
              dayMillis + (m * oneMinuteMillis)
            }) ++: missingSoFar
          }
          else missingSoFar
      }
      log.info(s"${missing.length} missing minutes took ${SDate.now().millisSinceEpoch - startMillis} milliseconds")
      missing.toSet
    }

    def minuteExists(millis: MillisSinceEpoch, terminals: List[TerminalName]): Boolean = terminals match {
      case Nil => false
      case t :: tail if staffMinutes.contains(TM(t, millis)) => true
      case _ :: tail => minuteExists(millis, tail)
    }

    setHandler(inShifts, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingShifts = grab(inShifts)
        log.info(s"Grabbed available inShifts")
        val updateCriteria = shiftsUpdateCriteria(shifts, incomingShifts)
        shifts = incomingShifts
        staffMinuteUpdates = updatesFromSources(staffSources, updateCriteria)
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
        staffMinuteUpdates = updatesFromSources(staffSources, updateCriteria)
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
        val latestUpdates = updatesFromSources(staffSources, updateCriteria)
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
        val date = SDate(m, Crunch.europeLondonTimeZone)
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
      val terminalNames = ((newAssignments -- oldAssignments) union (oldAssignments -- newAssignments)).map(_.terminalName)

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

    def updatesFromSources(staff: StaffSources, updateCriteria: UpdateCriteria): Map[TM, StaffMinute] = {
      log.info(s"about to update ${updateCriteria.minuteMillis.size} staff minutes for ${updateCriteria.terminalNames}")

      import SDate.implicits.sdateFromMilliDateLocal

      val updatedMinutes = updateCriteria.minuteMillis
        .flatMap(m => {
          updateCriteria.terminalNames.map { tn =>
            val shifts = staff.shifts.terminalStaffAt(tn, SDate(m))
            val fixedPoints = staff.fixedPoints.terminalStaffAt(tn, SDate(m, Crunch.europeLondonTimeZone))
            val movements = staff.movements.terminalStaffAt(tn, m)
            StaffMinute(tn, m, shifts, fixedPoints, movements, lastUpdated = Option(SDate.now().millisSinceEpoch))
          }
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
