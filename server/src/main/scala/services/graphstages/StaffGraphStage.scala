package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream._
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.{AirportConfig, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{europeLondonTimeZone, purgeExpired}

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
        val incomingStaffMinutes = grab(inStaffMinutes)
        val changedDays = incomingStaffMinutes.minutes.groupBy(sm => Crunch.getLocalLastMidnight(SDate(sm.minute, europeLondonTimeZone)).millisSinceEpoch)

        val updatedMinutes = changedDays.foldLeft(staffMinutesQueue.toMap) {
          case (soFar, (dayMillis, staffMinutes)) => soFar.updated(dayMillis, StaffMinutes(staffMinutes))
        }.toList.sortBy(_._1)

        staffMinutesQueue = Crunch.purgeExpired(updatedMinutes, now, expireAfterMillis)

        pushIfAvailable()

        pull(inStaffMinutes)
      }
    })

    setHandler(outStaffMinutes, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"onPull called. ${staffMinutesQueue.length} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inStaffMinutes)) pull(inStaffMinutes)
      }
    })

    def pushIfAvailable(): Unit = {
      staffMinutesQueue match {
        case Nil => log.info(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outStaffMinutes) =>
          log.info(s"outStaffMinutes not available to push")
        case (millis, staffMinutes) :: queueTail =>
          log.info(s"Pushing ${SDate(millis).toLocalDateTimeString()} staff minutes")
          push(outStaffMinutes, staffMinutes)

          staffMinutesQueue = queueTail
          log.info(s"Queue length now ${staffMinutesQueue.length}")
      }
    }
  }
}

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
    var staffMinutes: Map[Int, StaffMinute] = Map()
    var staffMinuteUpdates: Map[Int, StaffMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    def maybeStaffSources: Option[StaffSources] = Staffing.staffAvailableByTerminalAndQueue(shiftsOption, fixedPointsOption, movementsOption)

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
        log.info(s"minutesToUpdate: $minutesToUpdate")
        val latestUpdates = updatesFromSources(maybeStaffSources, minutesToUpdate)
        staffMinuteUpdates = mergeStaffMinuteUpdates(latestUpdates, staffMinuteUpdates)
        tryPush()
        pull(inMovements)
      }
    })

    def mergeStaffMinuteUpdates(latestUpdates: Map[Int, StaffMinute], existingUpdates: Map[Int, StaffMinute]): Map[Int, StaffMinute] = latestUpdates
      .foldLeft(existingUpdates) {
        case (soFar, (id, updatedMinute)) => soFar.updated(id, updatedMinute)
      }

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
                StaffMinute(tn, m, shifts, fixedPoints, movements, lastUpdated = Option(SDate.now().millisSinceEpoch))
            }
            staffMinute
          })
        })

      val mergedMinutes = updatedMinutes.foldLeft(staffMinutes) {
        case (soFar, updatedMinute) => soFar.updated(updatedMinute.key, updatedMinute)
      }

      staffMinutes = purgeExpired(mergedMinutes, (sm: StaffMinute) => sm.minute, now, expireAfterMillis)

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
      } else log.info(s"outStaffMinutes not available to push")
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
            date.millisSinceEpoch
          })
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
