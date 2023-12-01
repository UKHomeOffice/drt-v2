package services.exports

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{CrunchMinute, MinuteLike, StaffMinute}
import services.LocalDateStream
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object StaffRequirementExports {
  private val relevantMinute: (LocalDate, Seq[CrunchMinute]) => Seq[CrunchMinute] =
    (current, minutes) => minutes.filter { m => SDate(m.minute).toLocalDate == current }

  def queuesProvider(utcQueuesProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed],
                    ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, Seq[CrunchMinute]), NotUsed] =
    LocalDateStream(utcQueuesProvider, startBufferDays = 0, endBufferDays = 0, transformData = relevantMinute)

  def toPassengerHeadlines(queues: Seq[Queue]): (LocalDate, Seq[CrunchMinute]) => Seq[String] =
    (date, minutes) => {
      val dateFormatted = f"${date.day}%02d/${date.month}%02d"
      val total = minutes.map(_.paxLoad).sum.toInt
      val workLoad = minutes.map(_.workLoad).sum.toInt
      val byQueue = paxByQueue(minutes)

      val byQueueInOrder = Queues.inOrder(queues)
        .map(byQueue.get)
        .collect {
          case Some(pax) => pax
          case None => 0
        }
      Seq(dateFormatted, total.toString) ++ byQueueInOrder.map(_.toString) ++ Seq(workLoad.toString)
    }

  private def paxByQueue(minutes: Seq[CrunchMinute]): Map[Queues.Queue, Int] =
    minutes
      .map(m => (m.queue, m.paxLoad))
      .groupBy { case (queue, _) => queue }
      .map { case (queue, paxLoads) =>
        val pax = paxLoads.map {
          case (_, pax) => pax
        }.sum
        (queue, pax.toInt)
      }

  def toHourlyStaffing(staffProvider: LocalDate => Future[Seq[StaffMinute]], minutesInSlot: Int)
                      (implicit ec: ExecutionContext): (LocalDate, Seq[CrunchMinute]) => Future[Seq[(String, String, String)]] =
    (date, crunchMinutes) => {
      val numberOfSlots = 1440 / minutesInSlot
      val dateFormatted = f"${date.day}%02d/${date.month}%02d"
      staffProvider(date).map { staffMinutes =>
        val staffMinutesBySlot = groupByXMinutes(staffMinutes, minutesInSlot)
        val crunchMinutesBySlot = groupByXMinutes(crunchMinutes, minutesInSlot)

        val availableAndRequired: Seq[(String, String, String)] = (0 until numberOfSlots).map { slotNumber =>
          val slotCrunch = crunchMinutesBySlot.getOrElse(slotNumber, Seq())
          val slotStaff = staffMinutesBySlot.getOrElse(slotNumber, Seq())
          val available = if (slotStaff.nonEmpty) slotStaff.map(_.shifts).max else 0
          val required = maxRequired(slotCrunch, slotStaff)
          val diff = available - required
          (available.toString, required.toString, diff.toString)
        }

        val headings = (s"$dateFormatted - available", s"$dateFormatted - required", s"$dateFormatted - difference")
        Seq(headings) ++ availableAndRequired
      }
    }

  def maxRequired(crunchMinutes: Seq[CrunchMinute], staffMinutes: Seq[StaffMinute]): Int = {
    val deskRecsByMinute = crunchMinutes.groupBy(_.minute).view.mapValues(_.map(_.deskRec).sum).toMap
    val fixedPointsByMinute = staffMinutes.map(sm => (sm.minute, sm.fixedPoints)).toMap
    val reqs = deskRecsByMinute.map {
      case (minute, deskRec) => deskRec + fixedPointsByMinute.getOrElse(minute, 0)
    }.toSeq

    if (reqs.nonEmpty) reqs.max else 0
  }

  def groupByXMinutes[A, B](minutes: Seq[MinuteLike[A, B]], minutesInGroup: Int): Map[Int, Seq[A]] = minutes
    .groupBy { sm =>
      val localSDate = SDate(sm.minute, Crunch.europeLondonTimeZone)
      ((localSDate.getHours * 60) + localSDate.getMinutes) / minutesInGroup
    }
    .view
    .mapValues(_.map(_.toMinute))
    .toMap

  def staffingForLocalDateProvider(terminal: Terminal, utcProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[StaffMinute]), NotUsed])
                                  (implicit ec: ExecutionContext, mat: Materializer): LocalDate => Future[Seq[StaffMinute]] =
    date => {
      val startUtc = SDate(date).toUtcDate
      val endUtc = SDate(date).addDays(1).addMinutes(-1).toUtcDate
      utcProvider(startUtc, endUtc, terminal)
        .runWith(Sink.seq)
        .map { seq =>
          seq.map(_._2).flatMap { staffMinutes =>
            staffMinutes.filter(m => SDate(m.minute).toLocalDate == date)
          }
        }
    }

}
