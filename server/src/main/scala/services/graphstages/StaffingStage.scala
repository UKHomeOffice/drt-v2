package services.graphstages

import java.util.UUID

import actors.StaffMovements
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{CrunchResult, MilliDate, SDateLike, StaffMovement}
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch.CrunchState

import scala.collection.immutable.{Iterable, Map, Seq}
import scala.util.{Failure, Success, Try}

class StaffingStage()
  extends GraphStage[FanInShape4[CrunchState, String, String, Seq[StaffMovement], CrunchState]] {
  val inCrunch: Inlet[CrunchState] = Inlet[CrunchState]("CrunchStateWithoutSimulations.in")
  val inShifts: Inlet[String] = Inlet[String]("Shifts.in")
  val inFixedPoints: Inlet[String] = Inlet[String]("FixedPoints.in")
  val inMovements: Inlet[Seq[StaffMovement]] = Inlet[Seq[StaffMovement]]("Movements.in")
  val outCrunch: Outlet[CrunchState] = Outlet[CrunchState]("CrunchStateWithSimulations.out")

  var crunchState: Option[CrunchState] = None
  var shifts: Option[String] = None
  var fixedPoints: Option[String] = None
  var movements: Option[Seq[StaffMovement]] = None

  var crunchStateWithSimulation: Option[CrunchState] = None

  val log = LoggerFactory.getLogger(getClass)

  override def shape: FanInShape4[CrunchState, String, String, Seq[StaffMovement], CrunchState] =
    new FanInShape4(inCrunch, inShifts, inFixedPoints, inMovements, outCrunch)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      setHandler(inCrunch, new InHandler {
        override def onPush(): Unit = {
          log.info(s"inCrunch onPush() - setting crunchstate")
          crunchState = Option(grab(inCrunch))
          runSimulationAndPush
        }
      })

      setHandler(inShifts, new InHandler {
        override def onPush(): Unit = {
          log.info(s"inShifts onPush() - setting shifts")
          shifts = Option(grab(inShifts))
          runSimulationAndPush
        }
      })

      setHandler(inFixedPoints, new InHandler {
        override def onPush(): Unit = {
          log.info(s"inFixedPoints onPush() - setting fixedpoints")
          fixedPoints = Option(grab(inFixedPoints))
          runSimulationAndPush
        }
      })

      setHandler(inMovements, new InHandler {
        override def onPush(): Unit = {
          log.info(s"inMovements onPush() - setting movements")
          movements = Option(grab(inMovements))
          runSimulationAndPush
        }
      })

      def pushAndPull() = {
        crunchStateWithSimulation match {
          case None =>
            log.info(s"Nothing to push")
          case Some(cs) =>
            log.info(s"pushing CrunchStateWithSimulation")
            push(outCrunch, cs)
            crunchStateWithSimulation = None
        }
        if (!hasBeenPulled(inCrunch)) pull(inCrunch)
        if (!hasBeenPulled(inShifts)) pull(inShifts)
        if (!hasBeenPulled(inFixedPoints)) pull(inFixedPoints)
        if (!hasBeenPulled(inMovements)) pull(inMovements)
      }

      def runSimulationAndPush = {
        log.info(s"Running simulation")
        crunchStateWithSimulation = crunchState
        if (isAvailable(outCrunch)) pushAndPull()
      }

      def stuff = {
        lazy val staffDeploymentsByTerminalAndQueue = {
          val rawShiftsString = shifts match {
            case Some(rawShifts) => rawShifts
            case _ => ""
          }
          val rawFixedPointsString = fixedPoints match {
            case Some(rawFixedPoints) => rawFixedPoints
            case _ => ""
          }
          val mymovements = movements match {
            case Some(mm) => mm
            case _ => Seq()
          }

          val myshifts = StaffAssignmentParser(rawShiftsString).parsedAssignments.toList
          val myfixedPoints = StaffAssignmentParser(rawFixedPointsString).parsedAssignments.toList
          //todo we have essentially this code elsewhere, look for successfulShifts
          val staffFromShiftsAndMovementsAt = if (myshifts.exists(s => s.isFailure) || myfixedPoints.exists(s => s.isFailure)) {
            (t: TerminalName, m: MilliDate) => 0
          } else {
            val successfulShifts = myshifts.collect { case Success(s) => s }
            val ss = StaffAssignmentServiceWithDates(successfulShifts)

            val successfulFixedPoints = myfixedPoints.collect { case Success(s) => s }
            val fps = StaffAssignmentServiceWithoutDates(successfulFixedPoints)
            StaffMovements.terminalStaffAt(ss, fps)(mymovements) _
          }

          val pdr = PortDeployment.portDeskRecs(queueCrunchResults)
          val pd = PortDeployment.terminalDeployments(pdr, staffFromShiftsAndMovementsAt)
          val tsa = PortDeployment.terminalStaffAvailable(pd) _

          airportConfig match {
            case Ready(config) =>
              StaffDeploymentCalculator(tsa, queueCrunchResults, config.minMaxDesksByTerminalQueue).getOrElse(Map())
            case _ => Map()
          }
        }
      }

      setHandler(outCrunch, new OutHandler {
        override def onPull(): Unit = {
          log.info(s"outCrunch onPull() called")
          pushAndPull()
        }
      })
    }
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

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int
}

case class StaffAssignmentServiceWithoutDates(assignments: Seq[StaffAssignment]) extends StaffAssignmentService {

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int = assignments.filter(assignment => {
    assignment.terminalName == terminalName &&
      SDate(date).toHoursAndMinutes() >= SDate(assignment.startDt).toHoursAndMinutes() &&
      SDate(date).toHoursAndMinutes() <= SDate(assignment.endDt).toHoursAndMinutes()
  }).map(_.numberOfStaff).sum
}

case class StaffAssignmentServiceWithDates(assignments: Seq[StaffAssignment]) extends StaffAssignmentService {

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int = assignments.filter(assignment => {
    assignment.startDt <= date && date <= assignment.endDt && assignment.terminalName == terminalName
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

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: MilliDate): Int = movements.takeWhile(_.time <= dateTime).map(_.delta).sum

  def terminalStaffAt(assignmentService: StaffAssignmentService, fixedPointService: StaffAssignmentServiceWithoutDates)(movements: Seq[StaffMovement])(terminalName: TerminalName, dateTime: MilliDate): Int = {
    val baseStaff = assignmentService.terminalStaffAt(terminalName, dateTime)
    val fixedPointStaff = fixedPointService.terminalStaffAt(terminalName, dateTime)

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateTime)
    baseStaff - fixedPointStaff + movementAdjustments
  }
}

object PortDeployment {
  def portDeskRecs(portRecs: Map[TerminalName, Map[QueueName, CrunchResult]]): List[(Long, List[(Int, TerminalName)])] = {
    val portRecsByTerminal: List[List[(Int, TerminalName)]] = portRecs.map {
      case (terminalName, terminalCrunchResults) =>
        val values: Seq[CrunchResult] = terminalCrunchResults.values.toList
        val deskRecsByMinute: Seq[Seq[Int]] = values.transpose(_.recommendedDesks)

        deskRecsByMinute.map((x: Iterable[Int]) => x.sum).map((_, terminalName)).toList
      case _ => List()
    }.toList

    val seconds: Range = secondsRangeFromPortCrunchResult(portRecs.values.toList)
    val portRecsByTimeInSeconds: List[(Int, List[(Int, TerminalName)])] = seconds.zip(portRecsByTerminal.transpose).toList
    val portRecsByTimeInMillis: List[(Long, List[(Int, TerminalName)])] = portRecsByTimeInSeconds.map {
      case (seconds, deskRecsWithTerminal) => (seconds.toLong * 1000, deskRecsWithTerminal)
    }
    portRecsByTimeInMillis
  }

  def secondsRangeFromPortCrunchResult(terminalRecs: List[Map[QueueName, CrunchResult]]): Range = {
    val startMillis = for {
      queueRecs: Map[QueueName, CrunchResult] <- terminalRecs
      firstQueueRecs = queueRecs.values
      crunchResult <- firstQueueRecs
    } yield crunchResult.firstTimeMillis
    val firstSec = startMillis.headOption.getOrElse(0L) / 1000
    Range(firstSec.toInt, firstSec.toInt + (60 * 60 * 24), 60)
  }

  def terminalAutoDeployments(portDeskRecs: List[(Long, List[(Int, TerminalName)])], staffAvailable: MilliDate => Int): List[(Long, List[(Int, TerminalName)])] = {
    val roundToInt: (Double) => Int = _.toInt
    val deploymentsWithRounding = recsToDeployments(roundToInt) _
    portDeskRecs.map {
      case (millis, deskRecsWithTerminal) =>
        (millis, deploymentsWithRounding(deskRecsWithTerminal.map(_._1), staffAvailable(MilliDate(millis))).zip(deskRecsWithTerminal.map(_._2)).toList)
    }
  }

  def terminalDeployments(portDeskRecs: List[(Long, List[(Int, TerminalName)])], staffAvailable: (TerminalName, MilliDate) => Int): List[(Long, List[(Int, TerminalName)])] = {
    portDeskRecs.map {
      case (millis, deskRecsWithTerminal) =>
        val deploymentWithTerminal: List[(Int, TerminalName)] = deskRecsWithTerminal.map {
          case (_, terminalName) => staffAvailable(terminalName, MilliDate(millis))
        }.zip(deskRecsWithTerminal.map(_._2))

        (millis, deploymentWithTerminal)
    }
  }

  def recsToDeployments(round: Double => Int)(queueRecs: Seq[Int], staffAvailable: Int): Seq[Int] = {
    val totalStaffRec = queueRecs.sum
    queueRecs.foldLeft(List[Int]()) {
      case (agg, queueRec) if agg.length < queueRecs.length - 1 =>
        agg :+ round(staffAvailable * (queueRec.toDouble / totalStaffRec))
      case (agg, _) =>
        agg :+ staffAvailable - agg.sum
    }
  }

  def terminalStaffAvailable(deployments: List[(Long, List[(Int, TerminalName)])])(terminalName: TerminalName): (MilliDate) => Int = {
    val terminalDeployments: Map[Long, Int] = deployments.map(timeStaff => (timeStaff._1, timeStaff._2.find(_._2 == terminalName).map(_._1).getOrElse(0))).toMap
    (milliDate: MilliDate) => terminalDeployments.getOrElse(milliDate.millisSinceEpoch, 0)
  }
}
