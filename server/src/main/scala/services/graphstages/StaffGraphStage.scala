package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream._
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.FlightsApi.TerminalName
import drt.shared.{AirportConfig, SDateLike, StaffMovement, TM}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{europeLondonTimeZone, getLocalLastMidnight, movementsUpdateCriteria, purgeExpired}

import scala.util.Success

class StaffBatchUpdateGraphStage(now: () => SDateLike, expireAfterMillis: MillisSinceEpoch) extends GraphStage[FlowShape[StaffMinutes, StaffMinutes]] {
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
        val changedDays = incomingStaffMinutes.minutes.groupBy(sm => getLocalLastMidnight(SDate(sm.minute, europeLondonTimeZone)).millisSinceEpoch)

        val updatedMinutes = changedDays.foldLeft(staffMinutesQueue.toMap) {
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

case class UpdateCriteria(minuteMillis: Seq[MillisSinceEpoch], terminalNames: Set[TerminalName])

class StaffGraphStage(name: String = "",
                      optionalInitialShifts: Option[String],
                      optionalInitialFixedPoints: Option[String],
                      optionalInitialMovements: Option[Seq[StaffMovement]],
                      now: () => SDateLike,
                      expireAfterMillis: MillisSinceEpoch,
                      airportConfig: AirportConfig,
                      numberOfDays: Int) extends GraphStage[FanInShape3[String, String, Seq[StaffMovement], StaffMinutes]] {
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outStaffMinutes: Outlet[StaffMinutes] = Outlet[StaffMinutes]("StaffMinutes.out")

  override def shape: FanInShape3[String, String, Seq[StaffMovement], StaffMinutes] =
    new FanInShape3(inShifts, inFixedPoints, inMovements, outStaffMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    var shiftsOption: Option[String] = None
    var fixedPointsOption: Option[String] = None
    var movementsOption: Option[Seq[StaffMovement]] = None
    var staffMinutes: Map[TM, StaffMinute] = Map()
    var staffMinuteUpdates: Map[TM, StaffMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    val expireBefore = now().millisSinceEpoch - expireAfterMillis

    def maybeStaffSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(expireBefore, shiftsOption, fixedPointsOption, movementsOption)

    override def preStart(): Unit = {
      shiftsOption = optionalInitialShifts
      fixedPointsOption = optionalInitialFixedPoints
      movementsOption = optionalInitialMovements

      super.preStart()
    }

    setHandler(inShifts, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingShifts = grab(inShifts)
        log.info(s"Grabbed available inShifts")
        val updateCriteria = shiftsUpdateCriteria(shiftsOption.getOrElse(""), incomingShifts)
        shiftsOption = Option(incomingShifts)
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
        val updateCriteria = fixedPointsUpdateCriteria(fixedPointsOption.getOrElse(""), incomingFixedPoints)
        fixedPointsOption = Option(incomingFixedPoints)
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

    def shiftsUpdateCriteria(oldShifts: String, newShifts: String): UpdateCriteria = {
      val oldAssignments = assignmentsFromRawShifts(oldShifts)
      val newAssignments = assignmentsFromRawShifts(newShifts)

      val diff = newAssignments -- oldAssignments

      val minuteMillis = diff.flatMap(a => {
        val startMillis = a.startDt.millisSinceEpoch
        val endMillis = a.endDt.millisSinceEpoch
        startMillis to endMillis by 60000
      }).toSeq
      val terminalNames = diff.map(_.terminalName)

      UpdateCriteria(minuteMillis, terminalNames)
    }

    def fixedPointsUpdateCriteria(oldFixedPoints: String, newFixedPoints: String): UpdateCriteria = {
      val fpMinutesToUpdate = allMinuteMillis(newFixedPoints)
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

      val oldAssignments = assignmentsFromRawShifts(oldFixedPoints)
      val newAssignments = assignmentsFromRawShifts(newFixedPoints)

      val terminalNames = (newAssignments -- oldAssignments).map(_.terminalName)

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

      val updatedMinutes = updateCriteria.minuteMillis
        .flatMap(m => {
          updateCriteria.terminalNames.map(tn => {
            val staffMinute = staff match {
              case None => StaffMinute(tn, m, 0, 0, 0)
              case Some(staffSources) =>
                val shifts = staffSources.shifts.terminalStaffAt(tn, m)
                val fixedPoints = staffSources.fixedPoints.terminalStaffAt(tn, m)
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

  def allMinuteMillis(rawAssignments: String): Set[MillisSinceEpoch] = {
    assignmentsFromRawShifts(rawAssignments)
      .flatMap(a => {
        val startMillis = a.startDt.millisSinceEpoch
        val endMillis = a.endDt.millisSinceEpoch
        startMillis to endMillis by 60000
      })
  }

  def assignmentsFromRawShifts(rawAssignments: String): Set[StaffAssignment] = {
    StaffAssignmentParser(rawAssignments)
      .parsedAssignments
      .toList
      .collect {
        case Success(assignment) => assignment
      }
      .toSet
  }
}
