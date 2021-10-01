package services.graphstages

import java.util.UUID

import drt.shared.CrunchApi._
import drt.shared.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch.europeLondonTimeZone

import scala.collection.immutable.{NumericRange, SortedMap}
import scala.collection.mutable
import scala.util.Try


object Staffing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAvailableByTerminalAndQueue(dropBeforeMillis: MillisSinceEpoch,
                                       shifts: ShiftAssignments,
                                       fixedPoints: FixedPointAssignments,
                                       optionalMovements: Option[Seq[StaffMovement]]): StaffSources = {
    val movements = optionalMovements.getOrElse(Seq())

    val relevantShifts = removeOldShifts(dropBeforeMillis, shifts)

    val relevantMovements = removeOldMovements(dropBeforeMillis, movements)

    val movementsService = StaffMovementsService(relevantMovements)

    val available = StaffMovementsHelper.terminalStaffAt(relevantShifts, fixedPoints)(movements) _

    StaffSources(relevantShifts, fixedPoints, movementsService, available)
  }

  def removeOldShifts(dropBeforeMillis: MillisSinceEpoch, shifts: ShiftAssignments): ShiftAssignments = shifts.copy(
    shifts.assignments.collect { case sa: StaffAssignment if sa.endDt.millisSinceEpoch > dropBeforeMillis => sa }
  )

  def removeOldMovements(dropBeforeMillis: MillisSinceEpoch,
                         movements: Seq[StaffMovement]): Seq[StaffMovement] = movements
    .groupBy(_.uUID)
    .values
    .filter(_.exists(_.time.millisSinceEpoch > dropBeforeMillis))
    .flatten
    .toSeq
    .sortBy(_.time.millisSinceEpoch)

  def staffMinutesForCrunchMinutes(crunchMinutes: mutable.SortedMap[TQM, CrunchMinute],
                                   maybeSources: StaffSources): SortedMap[TM, StaffMinute] = {

    val staff = maybeSources
    SortedMap[TM, StaffMinute]() ++ crunchMinutes
      .values
      .groupBy(_.terminal)
      .flatMap {
        case (tn, tcms) =>
          val minutes = tcms.map(_.minute)
          val startMinuteMillis = minutes.min + oneMinuteMillis
          val endMinuteMillis = minutes.max
          val minuteMillis = startMinuteMillis to endMinuteMillis by oneMinuteMillis
          staffMinutesForPeriod(staff, tn, minuteMillis)
      }
  }

  def staffMinutesForPeriod(staff: StaffSources,
                            tn: Terminal,
                            minuteMillis: NumericRange[MillisSinceEpoch]): SortedMap[TM, StaffMinute] = {
    import SDate.implicits.sdateFromMilliDateLocal

    SortedMap[TM, StaffMinute]() ++ minuteMillis
      .map { minute =>
        val shifts = staff.shifts.terminalStaffAt(tn, SDate(minute))
        val fixedPoints = staff.fixedPoints.terminalStaffAt(tn, SDate(minute, Crunch.europeLondonTimeZone))
        val movements = staff.movements.terminalStaffAt(tn, minute)
        val staffMinute = StaffMinute(tn, minute, shifts, fixedPoints, movements)
        (staffMinute.key, staffMinute)
      }
  }
}

object StaffDeploymentCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  type Deployer = (Seq[(Queue, Int)], Int, Map[Queue, (Int, Int)]) => Seq[(Queue, Int)]

  def addDeployments(crunchMinutes: Map[TQM, CrunchMinute],
                     deployer: Deployer,
                     optionalStaffSources: Option[StaffSources],
                     minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]]): Map[TQM, CrunchMinute] = crunchMinutes
    .values
    .groupBy(_.terminal)
    .flatMap {
      case (tn, tCrunchMinutes) =>
        val terminalByMinute: Map[TQM, CrunchMinute] = tCrunchMinutes
          .groupBy(_.minute)
          .flatMap {
            case (minute, mCrunchMinutes) =>
              val deskRecAndQueueNames: Seq[(Queue, Int)] = mCrunchMinutes.map(cm => (cm.queue, cm.deskRec)).toSeq.sortBy(_._1)
              val queueMinMaxDesks: Map[Queue, (List[Int], List[Int])] = minMaxDesks.getOrElse(tn, Map())
              val minMaxByQueue: Map[Queue, (Int, Int)] = queueMinMaxDesks.map {
                case (qn, minMaxList) =>
                  val minDesks = DeskRecs.desksForHourOfDayInUKLocalTime(minute, minMaxList._1.toIndexedSeq)
                  val maxDesks = DeskRecs.desksForHourOfDayInUKLocalTime(minute, minMaxList._2.toIndexedSeq)
                  (qn, (minDesks, maxDesks))
              }
              val available = optionalStaffSources.map(_.available(minute, tn)).getOrElse(0)
              val deploymentsAndQueueNames: Map[Queue, Int] = deployer(deskRecAndQueueNames, available, minMaxByQueue).toMap
              mCrunchMinutes.map(cm => (cm.key, cm.copy(deployedDesks = Option(deploymentsAndQueueNames(cm.queue))))).toMap
          }
        terminalByMinute
    }

  def queueRecsToDeployments(round: Double => Int)
                            (queueRecs: Seq[(Queue, Int)],
                             staffAvailable: Int,
                             minMaxDesks: Map[Queue, (Int, Int)]): Seq[(Queue, Int)] = {
    val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1)) else queueRecs

    val totalStaffRec = queueRecsCorrected.map(_._2).sum

    queueRecsCorrected.foldLeft(List[(Queue, Int)]()) {
      case (agg, (queue, deskRec)) if agg.length < queueRecsCorrected.length - 1 =>
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

object StaffAssignmentHelper {
  def tryStaffAssignment(name: String,
                         terminalName: Terminal,
                         startDate: String,
                         startTime: String,
                         endTime: String,
                         numberOfStaff: String = "1"): Try[StaffAssignment] = {
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
      val start = startDt
      val end = adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt)
      StaffAssignment(name, terminalName, MilliDate(start.millisSinceEpoch), MilliDate(end.millisSinceEpoch), staffDelta, None)
    }
  }

  private def adjustEndDateIfEndTimeIsBeforeStartTime(d: Int,
                                                      m: Int,
                                                      y: Int,
                                                      startDt: SDateLike,
                                                      endDt: SDateLike): SDateLike = {
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
      val startDt = SDate(y = y, m = m, d = d, h = startHour, mm = startMinute, dateTimeZone = europeLondonTimeZone)
      startDt
    }
  }
}

case class StaffSources(shifts: ShiftAssignmentsLike,
                        fixedPoints: FixedPointAssignmentsLike,
                        movements: StaffAssignmentService,
                        available: (MillisSinceEpoch, Terminal) => Int)

trait StaffAssignmentService {
  def terminalStaffAt(terminalName: Terminal, dateMillis: MillisSinceEpoch): Int
}

case class StaffMovementsService(movements: Seq[StaffMovement])
  extends StaffAssignmentService {
  def terminalStaffAt(terminalName: Terminal, dateMillis: MillisSinceEpoch): Int = {
    StaffMovementsHelper.adjustmentsAt(movements.filter(_.terminal == terminalName))(dateMillis)
  }
}

object StaffMovementsHelper {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(assignment.terminal, assignment.name + " start", time = assignment.startDt, assignment.numberOfStaff, uuid, createdBy = None) ::
        StaffMovement(assignment.terminal, assignment.name + " end", time = assignment.endDt, -assignment.numberOfStaff, uuid, createdBy = None) :: Nil
    }).sortBy(_.time.millisSinceEpoch)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])
                   (dateTimeMillis: MillisSinceEpoch): Int = movements.takeWhile(_.time.millisSinceEpoch <= dateTimeMillis).map(_.delta).sum

  def terminalStaffAt(shifts: ShiftAssignments, fixedPoints: FixedPointAssignments)
                     (movements: Seq[StaffMovement])
                     (dateTimeMillis: MillisSinceEpoch, terminalName: Terminal): Int = {
    val baseStaff = shifts.terminalStaffAt(terminalName, SDate(dateTimeMillis))

    import SDate.implicits.sdateFromMilliDateLocal
    val fixedPointStaff = fixedPoints.terminalStaffAt(terminalName, SDate(dateTimeMillis, Crunch.europeLondonTimeZone))

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminal == terminalName))(dateTimeMillis)
    val staffAvailable = baseStaff - fixedPointStaff + movementAdjustments match {
      case sa if sa >= 0 => sa
      case _ => 0
    }

    staffAvailable
  }
}
