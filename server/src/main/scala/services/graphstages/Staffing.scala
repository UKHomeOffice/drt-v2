package services.graphstages

import java.util.UUID

import actors.pointInTime.{FixedPointsReadActor, ShiftsReadActor, StaffMovementsReadActor}
import actors.{GetState, StaffMovements}
import akka.actor.{ActorContext, ActorRef, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{desksForHourOfDayInUKLocalTime, europeLondonTimeZone}

import scala.collection.immutable.NumericRange
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.Try


object Staffing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAvailableByTerminalAndQueue(dropBeforeMillis: MillisSinceEpoch,
                                       shifts: ShiftAssignments,
                                       fixedPoints: FixedPointAssignments,
                                       optionalMovements: Option[Seq[StaffMovement]]): Option[StaffSources] = {
    val movements = optionalMovements.getOrElse(Seq())

    val relevantShifts = removeOldShifts(dropBeforeMillis, shifts)

    val relevantMovements = removeOldMovements(dropBeforeMillis, movements)

    val movementsService = StaffMovementsService(relevantMovements)

    val available = StaffMovementsHelper.terminalStaffAt(relevantShifts, fixedPoints)(movements) _

    Option(StaffSources(relevantShifts, fixedPoints, movementsService, available))
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

  def staffMinutesForCrunchMinutes(crunchMinutes: Map[TQM, CrunchMinute],
                                   maybeSources: Option[StaffSources]): Map[TM, StaffMinute] = {

    val staff = maybeSources
    crunchMinutes
      .values
      .groupBy(_.terminalName)
      .flatMap {
        case (tn, tcms) =>
          val minutes = tcms.map(_.minute)
          val startMinuteMillis = minutes.min + Crunch.oneMinuteMillis
          val endMinuteMillis = minutes.max
          val minuteMillis = startMinuteMillis to endMinuteMillis by Crunch.oneMinuteMillis
          log.info(s"Getting ${minuteMillis.size} staff minutes")
          staffMinutesForPeriod(staff, tn, minuteMillis)
      }
  }

  def staffMinutesForPeriod(staff: Option[StaffSources],
                            tn: TerminalName,
                            minuteMillis: NumericRange[MillisSinceEpoch]): Map[TM, StaffMinute] = {
    import SDate.implicits.sdateFromMilliDateLocal

    minuteMillis
      .map(m => {
        val staffMinute = staff match {
          case None => StaffMinute(tn, m, 0, 0, 0)
          case Some(staffSources) =>
            val shifts = staffSources.shifts.terminalStaffAt(tn, SDate(m))
            val fixedPoints = staffSources.fixedPoints.terminalStaffAt(tn, SDate(m, Crunch.europeLondonTimeZone))
            val movements = staffSources.movements.terminalStaffAt(tn, m)
            StaffMinute(tn, m, shifts, fixedPoints, movements)
        }
        (staffMinute.key, staffMinute)
      })
      .toMap
  }

  def reconstructStaffMinutes(pointInTime: SDateLike,
                              expireAfterMillis: Long,
                              context: ActorContext,
                              fl: Map[Int, ApiFlightWithSplits],
                              cm: Map[TQM, CrunchApi.CrunchMinute]): PortState = {
    val uniqueSuffix = pointInTime.toISOString + UUID.randomUUID.toString
    val shiftsActor: ActorRef = context.actorOf(Props(classOf[ShiftsReadActor], pointInTime, () => SDate(expireAfterMillis)), name = s"ShiftsReadActor-$uniqueSuffix")
    val askableShiftsActor: AskableActorRef = shiftsActor
    val fixedPointsActor: ActorRef = context.actorOf(Props(classOf[FixedPointsReadActor], pointInTime), name = s"FixedPointsReadActor-$uniqueSuffix")
    val askableFixedPointsActor: AskableActorRef = fixedPointsActor
    val staffMovementsActor: ActorRef = context.actorOf(Props(classOf[StaffMovementsReadActor], pointInTime, expireAfterMillis), name = s"StaffMovementsReadActor-$uniqueSuffix")
    val askableStaffMovementsActor: AskableActorRef = staffMovementsActor

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout: Timeout = new Timeout(30 seconds)

    val shiftsFuture: Future[ShiftAssignments] = askableShiftsActor.ask(GetState).map { case fp: ShiftAssignments => fp }.recoverWith { case _ => Future(ShiftAssignments.empty) }
    val fixedPointsFuture: Future[FixedPointAssignments] = askableFixedPointsActor.ask(GetState).map { case fp: FixedPointAssignments => fp }.recoverWith { case _ => Future(FixedPointAssignments.empty) }
    val movementsFuture: Future[Seq[StaffMovement]] = askableStaffMovementsActor.ask(GetState).map { case StaffMovements(sm) => sm }.recoverWith { case _ => Future(Seq()) }

    val shifts = Await.result(shiftsFuture, 1 minute)
    val fixedPoints = Await.result(fixedPointsFuture, 1 minute)
    val movements = Await.result(movementsFuture, 1 minute)

    shiftsActor ! PoisonPill
    fixedPointsActor ! PoisonPill
    staffMovementsActor ! PoisonPill

    val staffSources = Staffing.staffAvailableByTerminalAndQueue(0L, shifts, fixedPoints, Option(movements))
    val staffMinutes = Staffing.staffMinutesForCrunchMinutes(cm, staffSources)

    PortState(fl, cm, staffMinutes)
  }

}

object StaffDeploymentCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  type Deployer = (Seq[(String, Int)], Int, Map[String, (Int, Int)]) => Seq[(String, Int)]

  def addDeployments(crunchMinutes: Map[TQM, CrunchMinute],
                     deployer: Deployer,
                     optionalStaffSources: Option[StaffSources],
                     minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]]): Map[TQM, CrunchMinute] = crunchMinutes
    .values
    .groupBy(_.terminalName)
    .flatMap {
      case (tn, tCrunchMinutes) =>
        val terminalByMinute: Map[TQM, CrunchMinute] = tCrunchMinutes
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
              val available = optionalStaffSources.map(_.available(minute, tn)).getOrElse(0)
              val deploymentsAndQueueNames: Map[String, Int] = deployer(deskRecAndQueueNames, available, minMaxByQueue).toMap
              mCrunchMinutes.map(cm => (cm.key, cm.copy(deployedDesks = Option(deploymentsAndQueueNames(cm.queueName))))).toMap
          }
        terminalByMinute
    }

  def queueRecsToDeployments(round: Double => Int)
                            (queueRecs: Seq[(String, Int)],
                             staffAvailable: Int,
                             minMaxDesks: Map[String, (Int, Int)]): Seq[(String, Int)] = {
    val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1)) else queueRecs

    val totalStaffRec = queueRecsCorrected.map(_._2).sum

    queueRecsCorrected.foldLeft(List[(String, Int)]()) {
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
                         terminalName: TerminalName,
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
                        available: (MillisSinceEpoch, TerminalName) => Int)

trait StaffAssignmentService {
  def terminalStaffAt(terminalName: TerminalName, dateMillis: MillisSinceEpoch): Int
}

case class StaffMovementsService(movements: Seq[StaffMovement])
  extends StaffAssignmentService {
  def terminalStaffAt(terminalName: TerminalName, dateMillis: MillisSinceEpoch): Int = {
    StaffMovementsHelper.adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateMillis)
  }
}

object StaffMovementsHelper {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(assignment.terminalName, assignment.name + " start", time = assignment.startDt, assignment.numberOfStaff, uuid, createdBy = None) ::
        StaffMovement(assignment.terminalName, assignment.name + " end", time = assignment.endDt, -assignment.numberOfStaff, uuid, createdBy = None) :: Nil
    }).sortBy(_.time.millisSinceEpoch)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])
                   (dateTimeMillis: MillisSinceEpoch): Int = movements.takeWhile(_.time.millisSinceEpoch <= dateTimeMillis).map(_.delta).sum

  def terminalStaffAt(shifts: ShiftAssignments, fixedPoints: FixedPointAssignments)
                     (movements: Seq[StaffMovement])
                     (dateTimeMillis: MillisSinceEpoch, terminalName: TerminalName): Int = {
    val baseStaff = shifts.terminalStaffAt(terminalName, SDate(dateTimeMillis))

    import SDate.implicits.sdateFromMilliDateLocal
    val fixedPointStaff = fixedPoints.terminalStaffAt(terminalName, SDate(dateTimeMillis, Crunch.europeLondonTimeZone))

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateTimeMillis)
    val staffAvailable = baseStaff - fixedPointStaff + movementAdjustments match {
      case sa if sa >= 0 => sa
      case _ => 0
    }

    staffAvailable
  }
}
