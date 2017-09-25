package services.graphstages

import java.util.UUID

import actors.StaffMovements
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import diode.data.Pot
import diode.{Effect, EffectSeq, NoAction}
import drt.client.services.DeskRecTimeSlots
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{CrunchResult, MilliDate, SDateLike, StaffMovement}
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch.{CrunchMinute, CrunchState}
import services.graphstages.HandyStuff.QueueStaffDeployments
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable.{Iterable, Map, Seq}
import scala.concurrent.Future
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
          val rawShiftsString = shifts.getOrElse("")
          val rawFixedPointsString = fixedPoints.getOrElse("")
          val mymovements = movements.getOrElse(Seq())

          val myshifts = StaffAssignmentParser(rawShiftsString).parsedAssignments.toList
          val myfixedPoints = StaffAssignmentParser(rawFixedPointsString).parsedAssignments.toList
          val staffFromShiftsAndMovementsAt = if (myshifts.exists(s => s.isFailure) || myfixedPoints.exists(s => s.isFailure)) {
            (t: TerminalName, m: MilliDate) => 0
          } else {
            val successfulShifts = myshifts.collect { case Success(s) => s }
            val ss = StaffAssignmentServiceWithDates(successfulShifts)

            val successfulFixedPoints = myfixedPoints.collect { case Success(s) => s }
            val fps = StaffAssignmentServiceWithoutDates(successfulFixedPoints)
            StaffMovements.terminalStaffAt(ss, fps)(mymovements) _
          }

          val pdr = PortDeployment.portDeskRecs(crunchState.get.crunchMinutes)
          val pd = PortDeployment.terminalDeployments(pdr, staffFromShiftsAndMovementsAt)
          val tsa = PortDeployment.terminalStaffAvailable(pd) _


          StaffDeploymentCalculator(tsa, queueCrunchResults, config.minMaxDesksByTerminalQueue).getOrElse(Map())
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

object HandyStuff {
//  type PotCrunchResult = Pot[CrunchResult]
  type QueueStaffDeployments = Map[String, DeskRecTimeSlots]
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]
}


object StaffDeploymentCalculator {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def apply[M](
                staffAvailable: (TerminalName) => (MilliDate) => Int,
                terminalQueueCrunchResultsModel: Map[TerminalName, QueueCrunchResults],
                queueMinAndMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]]
              ):
  Try[TerminalQueueStaffDeployments] = {

    val terminalQueueCrunchResults = terminalQueueCrunchResultsModel
    val firstTerminalName = terminalQueueCrunchResults.keys.headOption.getOrElse("")
    val crunchResultWithTimeAndIntervalTry = Try(terminalQueueCrunchResults(firstTerminalName).head._2)

    crunchResultWithTimeAndIntervalTry.map(crunchResultWithTimeAndInterval => {

      val drts: DeskRecTimeSlots = calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval)

      val newSuggestedStaffDeployments: Map[TerminalName, Map[QueueName, Ready[DeskRecTimeSlots]]] = terminalQueueCrunchResults.map((terminalQueueCrunchResult: (TerminalName, QueueCrunchResults)) => {
        val terminalName: TerminalName = terminalQueueCrunchResult._1
        val terminalStaffAvailable = staffAvailable(terminalName)
        val queueCrunchResult = terminalQueueCrunchResult._2

        /*
         Fixme: This transpose loses the queue name and thus certainty of order
         */
        val queueDeskRecsOverTime: Iterable[Iterable[DeskRecTimeslot]] = queueCrunchResult.transpose {
          case (_, cr) => calculateDeskRecTimeSlots(cr).items
        }
        val timeslotsToInts = (deskRecTimeSlots: Iterable[DeskRecTimeslot]) => {
          val timeInMillis = MilliDate(deskRecTimeSlots.headOption.map(_.timeInMillis).getOrElse(0L))
          val queueNames = terminalQueueCrunchResults(terminalName).keys
          val deskRecs: Iterable[(Int, QueueName)] = deskRecTimeSlots.map(_.deskRec).zip(queueNames)
          val deps = queueRecsToDeployments(_.toInt)(deskRecs.toList, terminalStaffAvailable(timeInMillis), minMaxDesksForTime(queueMinAndMaxDesks(terminalName), timeInMillis.millisSinceEpoch))
          deps
        }
        val deployments = queueDeskRecsOverTime.map(timeslotsToInts).transpose
        val times: Seq[Long] = drts.items.map(_.timeInMillis)
        val zipped = queueCrunchResult.keys.zip({
          deployments.map(times.zip(_).map { case (t, r) => DeskRecTimeslot(t, r) })
        })
        (terminalName, zipped.toMap.mapValues((x: Seq[DeskRecTimeslot]) => Ready(DeskRecTimeSlots(x))))
      })

      newSuggestedStaffDeployments
    })
  }

  def minMaxDesksForTime(minMaxDesks: Map[QueueName, (List[Int], List[Int])], timestamp: Long): Map[QueueName, (Int, Int)] = {
    import JSDateConversions._
    val hour = MilliDate(timestamp).getHours()
    minMaxDesks.mapValues(minMaxForQueue => (minMaxForQueue._1(hour), minMaxForQueue._2(hour)))
  }

  def deploymentWithinBounds(min: Int, max: Int, ideal: Int, staffAvailable: Int) = {
    val best = if (ideal < min) min
    else if (ideal > max) max
    else ideal

    if (best > staffAvailable) staffAvailable
    else best
  }

  def calculateDeskRecTimeSlots(crunchResultWithTimeAndInterval: CrunchResult) = {
    val timeIntervalMinutes = 15
    val millis = Iterator.iterate(crunchResultWithTimeAndInterval.firstTimeMillis)(_ + timeIntervalMinutes * crunchResultWithTimeAndInterval.intervalMillis).toIterable

    val updatedDeskRecTimeSlots: DeskRecTimeSlots = DeskRecTimeSlots(
      TableViewUtils
        .takeEveryNth(timeIntervalMinutes)(crunchResultWithTimeAndInterval.recommendedDesks)
        .zip(millis).map {
        case (deskRec, timeInMillis) => DeskRecTimeslot(timeInMillis = timeInMillis, deskRec = deskRec)
      }.toList)
    updatedDeskRecTimeSlots
  }

  def queueRecsToDeployments(round: Double => Int)(queueRecs: List[(Int, String)], staffAvailable: Int, minMaxDesks: Map[QueueName, (Int, Int)]): Seq[Int] = {
    val totalStaffRec = queueRecs.map(_._1).sum

    queueRecs.foldLeft(List[Int]()) {
      case (agg, (deskRec, queue)) if agg.length < queueRecs.length - 1 =>
        val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
        agg :+ deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - agg.sum)
      case (agg, (_, queue)) =>
        val ideal = staffAvailable - agg.sum
        agg :+ deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - agg.sum)
    }
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
  def portRecs(crunchMinutes: Set[CrunchMinute]) = {
    crunchMinutes
      .groupBy(_.minute)
      .map {
        case (m, mcms) =>
          val byTerminal: Seq[(Int, TerminalName)] = mcms
            .groupBy(_.terminalName)
            .map {
              case (tn, tcms) => (tcms.map(_.deskRec).sum, tn)
            }
            .toList
          (m, byTerminal)
      }
      .toList
      .sortBy(_._1)
  }

  def portDeskRecs(crunchMinutes: Set[CrunchMinute]): List[(Long, List[(Int, TerminalName)])] = {
    val portRecsByTerminal: Seq[(MillisSinceEpoch, Seq[(Int, TerminalName)])] = portRecs(crunchMinutes)

    val minutes = crunchMinutes.map(_.minute)
    val seconds = Range((minutes.min / 1000).toInt, (minutes.max / 1000).toInt, 60)
    val transposed: Seq[List[Nothing]] = portRecsByTerminal.toList.transpose
    val portRecsByTimeInSeconds: List[(Int, List[(Int, TerminalName)])] = seconds.zip(transposed).toList
    val portRecsByTimeInMillis: List[(Long, List[(Int, TerminalName)])] = portRecsByTimeInSeconds.map {
      case (seconds, deskRecsWithTerminal) => (seconds.toLong * 1000, deskRecsWithTerminal)
    }
    portRecsByTimeInMillis
  }

  def terminalAutoDeployments(portDeskRecs: List[(Long, List[(Int, TerminalName)])], staffAvailable: MilliDate => Int): List[(Long, List[(Int, TerminalName)])] = {
    val roundToInt: (Double) => Int = _.toInt
    val deploymentsWithRounding: (Seq[Int], Int) => Seq[Int] = recsToDeployments(roundToInt) _
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
