package services.graphstages

import java.util.UUID

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, PortState, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{MilliDate, Queues, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.desksForHourOfDayInUKLocalTime
import services.graphstages.StaffDeploymentCalculator.{addDeployments, queueRecsToDeployments}
import services.{OptimizerConfig, SDate, TryRenjin}

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class StaffingStage(name: String, initialOptionalPortState: Option[PortState], minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]], slaByQueue: Map[QueueName, Int], warmUpMinutes: Int)
  extends GraphStage[FanInShape4[PortState, String, String, Seq[StaffMovement], PortState]] {
  val inCrunch: Inlet[PortState] = Inlet[PortState]("PortStateWithoutSimulations.in")
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outCrunch: Outlet[PortState] = Outlet[PortState]("PortStateWithSimulations.out")

  val allInlets = List(inShifts, inFixedPoints, inMovements, inCrunch)

  var portStateOption: Option[PortState] = None
  var shiftsOption: Option[String] = None
  var fixedPointsOption: Option[String] = None
  var movementsOption: Option[Seq[StaffMovement]] = None

  var stateAwaitingPush = false

  var portStateWithSimulation: Option[PortState] = None

  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  override def shape: FanInShape4[PortState, String, String, Seq[StaffMovement], PortState] =
    new FanInShape4(inCrunch, inShifts, inFixedPoints, inMovements, outCrunch)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {

      override def preStart(): Unit = {
        initialOptionalPortState match {
          case Some(initialPortState: PortState) =>
            log.info(s"Received initial portState")
            portStateOption = Option(initialPortState)
          case _ => log.info(s"Didn't receive any initial PortState")
        }

        super.preStart()
      }
      setHandler(inCrunch, new InHandler {
        override def onPush(): Unit = {
          grabAllAvailable()
          runSimulationAndPush(5)
        }
      })

      setHandler(inShifts, new InHandler {
        override def onPush(): Unit = {
          grabAllAvailable()
          runSimulationAndPush(5)
        }
      })

      setHandler(inFixedPoints, new InHandler {
        override def onPush(): Unit = {
          grabAllAvailable()
          runSimulationAndPush(5)
        }
      })

      setHandler(inMovements, new InHandler {
        override def onPush(): Unit = {
          grabAllAvailable()
          runSimulationAndPush(5)
        }
      })

      setHandler(outCrunch, new OutHandler {
        override def onPull(): Unit = pushAndPull()
      })


      def mergePortState(portStateOption: Option[PortState], newState: PortState): PortState = {
        portStateOption match {
          case None => newState
          case Some(existingState) =>
            PortState(
              flights = newState.flights,
              crunchMinutes = newState.crunchMinutes.foldLeft(existingState.crunchMinutes) {
                case (soFar, (idx, cm)) => soFar.updated(idx, cm)
              },
              staffMinutes = newState.staffMinutes.foldLeft(existingState.staffMinutes) {
                case (soFar, (idx, sm)) => soFar.updated(idx, sm)
              }
            )
        }
      }

      def grabAllAvailable(): Unit = {
        if (isAvailable(inShifts)) {
          log.info(s"Grabbing available inShifts")
          shiftsOption = Option(grab(inShifts))
        }
        if (isAvailable(inFixedPoints)) {
          log.info(s"Grabbing available inFixedPoints")
          fixedPointsOption = Option(grab(inFixedPoints))
        }
        if (isAvailable(inMovements)) {
          log.info(s"Grabbing available inMovements")
          movementsOption = Option(grab(inMovements))
        }
        if (isAvailable(inCrunch)) {
          log.info(s"Grabbing available inCrunch")
          portStateOption = Option(mergePortState(portStateOption, grab(inCrunch)))
          stateAwaitingPush = true
        }
      }

      def runSimulationAndPush(eGateBankSize: Int): Unit = {
        portStateWithSimulation = portStateOption match {
          case None =>
            log.info(s"No crunch to run simulations on")
            None
          case Some(ps@PortState(_, crunchMinutes, _)) =>
            log.info(s"Running simulations")
            val staffMinutes: Map[Int, StaffMinute] = staffMinutesForCrunchMinutes(crunchMinutes)
            val crunchMinutesWithDeployments = addDeployments(crunchMinutes, queueRecsToDeployments(_.toInt), staffAvailableByTerminalAndQueue, minMaxDesks)
            val crunchMinutesWithSimulation = addSimulationNumbers(crunchMinutesWithDeployments, eGateBankSize)
            Option(ps.copy(crunchMinutes = crunchMinutesWithSimulation, staffMinutes = staffMinutes))
        }
        stateAwaitingPush = true

        pushAndPull()
      }

      def addSimulationNumbers(crunchMinutesWithDeployments: Map[Int, CrunchMinute], eGateBankSize: Int): Map[Int, CrunchMinute] = {
        val minutesInACrunch = 1440
        val minutesInACrunchWithWarmUp = minutesInACrunch + warmUpMinutes

        crunchMinutesWithDeployments.values.groupBy(_.terminalName).flatMap {
          case (_, tCrunchMinutes) =>
            tCrunchMinutes.groupBy(_.queueName).flatMap {
              case (qn, qCrunchMinutes) =>
                qCrunchMinutes
                  .toList
                  .sortBy(_.minute)
                  .sliding(minutesInACrunchWithWarmUp, minutesInACrunch)
                  .flatMap(cms => {
                    val allMillis = cms.map(_.minute)
                    val firstMinute = SDate(allMillis.min)
                    val lastMinute = SDate(allMillis.max)
                    val minWlSd = cms.map(cm => Tuple3(cm.minute, cm.workLoad, cm.deployedDesks))
                    val workLoads = minWlSd.map {
                      case (_, wl, _) if qn == Queues.EGate => adjustEgateWorkload(eGateBankSize, wl)
                      case (_, wl, _) => wl
                    }
                    val deployedDesks = minWlSd.map { case (_, _, sd) => sd.getOrElse(0) }
                    val config = OptimizerConfig(slaByQueue(qn))
                    log.info(s"Running simulation on ${workLoads.length} workloads, ${deployedDesks.length} desks - ${firstMinute.toLocalDateTimeString()} to ${lastMinute.toLocalDateTimeString()}")
                    val simWaits = TryRenjin
                      .runSimulationOfWork(workLoads, deployedDesks, config)
                      .drop(warmUpMinutes)
                    log.info(s"Finished running simulation.")
                    cms
                      .sortBy(_.minute)
                      .drop(warmUpMinutes)
                      .zipWithIndex
                      .map {
                        case (cm, idx) => (cm.key, cm.copy(deployedWait = Option(simWaits(idx))))
                      }
                  })
            }
        }
      }

      def staffMinutesForCrunchMinutes(crunchMinutes: Map[Int, CrunchMinute]): Map[Int, StaffMinute] = {
        crunchMinutes
          .values
          .groupBy(_.terminalName)
          .flatMap {
            case (tn, tcms) =>
              val minutes = tcms.map(_.minute)
              val startMinuteMillis = minutes.min + (warmUpMinutes * Crunch.oneMinuteMillis)
              val endMinuteMillis = minutes.max
              val minuteMillis = startMinuteMillis to endMinuteMillis by Crunch.oneMinuteMillis
              log.info(s"Getting ${minuteMillis.size} staff minutes")
              minuteMillis
                .map(m => {
                  val available = staffAvailableByTerminalAndQueue(m, tn)
                  val staffMinute = StaffMinute(tn, m, available)
                  (staffMinute.key, staffMinute)
                })
                .toMap
          }
      }

      def pushAndPull(): Unit = {
        if (isAvailable(outCrunch))
          (portStateWithSimulation, stateAwaitingPush) match {
            case (None, _) =>
              log.info(s"No state to push yet")
            case (Some(_), false) =>
              log.info(s"No updates to push")
            case (Some(ps), true) =>
              log.info(s"Pushing PortStateWithSimulation")
              push(outCrunch, ps)
              stateAwaitingPush = false
          }
        else log.info(s"outCrunch not available to push")

        allInlets.foreach(inlet => if (!hasBeenPulled(inlet)) pull(inlet))
      }

      def staffAvailableByTerminalAndQueue: (MillisSinceEpoch, TerminalName) => Int = {
        val rawShiftsString = shiftsOption.getOrElse("")
        val rawFixedPointsString = fixedPointsOption.getOrElse("")
        val myMovements = movementsOption.getOrElse(Seq())

        val myShifts = StaffAssignmentParser(rawShiftsString).parsedAssignments.toList
        val myFixedPoints = StaffAssignmentParser(rawFixedPointsString).parsedAssignments.toList

        if (myShifts.exists(s => s.isFailure) || myFixedPoints.exists(s => s.isFailure)) {
          (_: MillisSinceEpoch, _: TerminalName) => 0
        } else {
          val successfulShifts = myShifts.collect { case Success(s) => s }
          val ss = StaffAssignmentServiceWithDates(successfulShifts)

          val successfulFixedPoints = myFixedPoints.collect { case Success(s) => s }
          val fps = StaffAssignmentServiceWithoutDates(successfulFixedPoints)
          StaffMovements.terminalStaffAt(ss, fps)(myMovements)
        }
      }
    }
  }

  def adjustEgateWorkload(eGateBankSize: Int, wl: Double): Double = {
    wl / eGateBankSize
  }
}

case class StaffAssignment(name: String, terminalName: TerminalName, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int) {
  def toCsv: String = {
    val startDate: SDateLike = SDate(startDt)
    val endDate: SDateLike = SDate(endDt)
    val startDateString = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    val startTimeString = f"${startDate.getHours()}%02d:${startDate.getMinutes()}%02d"
    val endTimeString = f"${endDate.getHours()}%02d:${endDate.getMinutes()}%02d"

    s"$name,$terminalName,$startDateString,$startTimeString,$endTimeString,$numberOfStaff"
  }
}

object StaffDeploymentCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  type Deployer = (Seq[(String, Int)], Int, Map[String, (Int, Int)]) => Seq[(String, Int)]

  def addDeployments(crunchMinutes: Map[Int, CrunchMinute],
                     deployer: Deployer,
                     available: (MillisSinceEpoch, QueueName) => Int,
                     minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]]): Map[Int, CrunchMinute] = crunchMinutes
    .values
    .groupBy(_.terminalName)
    .flatMap {
      case (tn, tCrunchMinutes) =>
        val terminalByMinute: Map[Int, CrunchMinute] = tCrunchMinutes
          .groupBy(_.minute)
          .flatMap {
            case (minute, mCrunchMinutes) =>
              val deskRecAndQueueNames: Seq[(QueueName, Int)] = mCrunchMinutes.map(cm => (cm.queueName, cm.deskRec)).toSeq.sortBy(_._1)
              val queueMinMaxDesks: Map[QueueName, (List[Int], List[Int])] = minMaxDesks.getOrElse(tn, Map())
              val minMaxByQueue: Map[QueueName, (Int, Int)] = queueMinMaxDesks.map {
                case (qn, minMaxList) =>
                  val minDesks = desksForHourOfDayInUKLocalTime(minute, minMaxList._1)
                  val maxDesks = desksForHourOfDayInUKLocalTime(minute, minMaxList._2)
                  (qn, (minDesks, maxDesks))
              }
              val deploymentsAndQueueNames: Map[String, Int] = deployer(deskRecAndQueueNames, available(minute, tn), minMaxByQueue).toMap
              mCrunchMinutes.map(cm => (cm.key, cm.copy(deployedDesks = Option(deploymentsAndQueueNames(cm.queueName))))).toMap
          }
        terminalByMinute
    }

  def queueRecsToDeployments(round: Double => Int)
                            (queueRecs: Seq[(String, Int)], staffAvailable: Int, minMaxDesks: Map[String, (Int, Int)]): Seq[(String, Int)] = {
    val totalStaffRec = queueRecs.map(_._2).sum

    queueRecs.foldLeft(List[(String, Int)]()) {
      case (agg, (queue, deskRec)) if agg.length < queueRecs.length - 1 =>
        val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
        val totalRecommended = agg.map(_._2).sum
        val dr = deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - totalRecommended)
        agg :+ Tuple2(queue, dr)
      case (agg, (queue, _)) =>
        val totalRecommended = agg.map(_._2).sum
        val ideal = staffAvailable - totalRecommended
        val dr = deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - totalRecommended)
        agg :+ Tuple2(queue, dr)
    }
  }

  def deploymentWithinBounds(min: Int, max: Int, ideal: Int, staffAvailable: Int): Int = {
    val best = if (ideal < min) min
    else if (ideal > max) max
    else ideal

    if (best > staffAvailable) staffAvailable
    else best
  }

}

object StaffAssignment {
  def apply(name: String, terminalName: TerminalName, startDate: String, startTime: String, endTime: String, numberOfStaff: String = "1"): Try[StaffAssignment] = {
    val staffDeltaTry = Try(numberOfStaff.toInt)
    val ymd = startDate.split("/").toVector

    val tryDMY: Try[(Int, Int, Int)] = Try((ymd(0).toInt, ymd(1).toInt, ymd(2).toInt + 2000))

    for {
      dmy <- tryDMY
      (d, m, y) = dmy

      startDtTry: Try[SDateLike] = parseTimeWithStartTime(startTime, d, m, y)
      endDtTry: Try[SDateLike] = parseTimeWithStartTime(endTime, d, m, y)
      startDt <- startDtTry
      endDt <- endDtTry
      staffDelta: Int <- staffDeltaTry
    } yield {
      val start = MilliDate(startDt.millisSinceEpoch)
      val end = MilliDate(adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt).millisSinceEpoch)
      StaffAssignment(name, terminalName, start, end, staffDelta)
    }
  }

  private def adjustEndDateIfEndTimeIsBeforeStartTime(d: Int, m: Int, y: Int, startDt: SDateLike, endDt: SDateLike): SDateLike = {
    if (endDt.millisSinceEpoch < startDt.millisSinceEpoch) {
      SDate(y, m, d, endDt.getHours(), endDt.getMinutes()).addDays(1)
    }
    else {
      endDt
    }
  }

  private def parseTimeWithStartTime(startTime: String, d: Int, m: Int, y: Int): Try[SDateLike] = {
    Try {
      val startT = startTime.split(":").toVector
      val (startHour, startMinute) = (startT(0).toInt, startT(1).toInt)
      val startDt = SDate(y, m, d, startHour, startMinute)
      startDt
    }
  }
}

case class StaffAssignmentParser(rawStaffAssignments: String) {
  val lines: Array[TerminalName] = rawStaffAssignments.split("\n")
  val parsedAssignments: Array[Try[StaffAssignment]] = lines.map(l => {
    l.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim)
  })
    .filter(parts => parts.length == 5 || parts.length == 6)
    .map {
      case List(description, terminalName, startDay, startTime, endTime) =>
        StaffAssignment(description, terminalName, startDay, startTime, endTime)
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        StaffAssignment(description, terminalName, startDay, startTime, endTime, staffNumberDelta)
    }
}

trait StaffAssignmentService {
  def terminalStaffAt(terminalName: TerminalName, dateMillis: MillisSinceEpoch): Int
}

case class StaffAssignmentServiceWithoutDates(assignments: Seq[StaffAssignment])
  extends StaffAssignmentService {
  def terminalStaffAt(terminalName: TerminalName, dateMillis: MillisSinceEpoch): Int = assignments.filter(assignment => {
    assignment.terminalName == terminalName &&
      SDate(dateMillis).toHoursAndMinutes() >= SDate(assignment.startDt).toHoursAndMinutes() &&
      SDate(dateMillis).toHoursAndMinutes() <= SDate(assignment.endDt).toHoursAndMinutes()
  }).map(_.numberOfStaff).sum
}

case class StaffAssignmentServiceWithDates(assignments: Seq[StaffAssignment])
  extends StaffAssignmentService {
  def terminalStaffAt(terminalName: TerminalName, dateMillis: MillisSinceEpoch): Int = assignments.filter(assignment => {
    assignment.startDt.millisSinceEpoch <= dateMillis && dateMillis <= assignment.endDt.millisSinceEpoch && assignment.terminalName == terminalName
  }).map(_.numberOfStaff).sum
}

object StaffAssignmentServiceWithoutDates {
  def apply(assignments: Seq[Try[StaffAssignment]]): Try[StaffAssignmentServiceWithoutDates] = {
    if (assignments.exists(_.isFailure))
      Failure(new Exception("Couldn't parse assignments"))
    else {
      Success(StaffAssignmentServiceWithoutDates(assignments.collect { case Success(s) => s }))
    }
  }
}

object StaffAssignmentServiceWithDates {
  def apply(assignments: Seq[Try[StaffAssignment]]): Try[StaffAssignmentServiceWithDates] = {
    if (assignments.exists(_.isFailure))
      Failure(new Exception("Couldn't parse assignments"))
    else {
      Success(StaffAssignmentServiceWithDates(assignments.collect { case Success(s) => s }))
    }
  }
}

object StaffMovements {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(assignment.terminalName, assignment.name + " start", time = assignment.startDt, assignment.numberOfStaff, uuid) ::
        StaffMovement(assignment.terminalName, assignment.name + " end", time = assignment.endDt, -assignment.numberOfStaff, uuid) :: Nil
    }).sortBy(_.time)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTimeMillis: MillisSinceEpoch): Int = movements.takeWhile(_.time.millisSinceEpoch <= dateTimeMillis).map(_.delta).sum

  def terminalStaffAt(assignmentService: StaffAssignmentService, fixedPointService: StaffAssignmentServiceWithoutDates)
                     (movements: Seq[StaffMovement])
                     (dateTimeMillis: MillisSinceEpoch, terminalName: TerminalName): Int = {
    val baseStaff = assignmentService.terminalStaffAt(terminalName, dateTimeMillis)
    val fixedPointStaff = fixedPointService.terminalStaffAt(terminalName, dateTimeMillis)

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateTimeMillis)
    baseStaff - fixedPointStaff + movementAdjustments
  }
}
