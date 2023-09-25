package services.exports

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import services.LocalDateStream
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
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

  def toHourlyStaffing(staffProvider: LocalDate => Future[Seq[StaffMinute]])
                      (implicit ec: ExecutionContext): (LocalDate, Seq[CrunchMinute]) => Future[Seq[(String, String, String)]] =
    (date, crunchMinutes) => {
      val dateFormatted = f"${date.day}%02d/${date.month}%02d"
      val startMinute = SDate(date)
      staffProvider(date).map { staffMinutes =>
        val staffByMinute = staffMinutes.groupBy(_.minute)
        val staffMinutesByQuarterHour = staffMinutes.groupBy { sm =>
          val localSDate = SDate(sm.minute, Crunch.europeLondonTimeZone)
          ((localSDate.getHours * 60) + localSDate.getMinutes) /15
        }
        val crunchMinutesByQuarterHour = crunchMinutes.groupBy { cm =>
          val localSDate = SDate(cm.minute, Crunch.europeLondonTimeZone)
          ((localSDate.getHours * 60) + localSDate.getMinutes) /15
        }
        val availableAndRequired: Seq[(String, String, String)] = (0 until 96).map { quarterHour =>
          val slotStart = startMinute.addMinutes(quarterHour * 15)
          val slotEnd = slotStart.addMinutes(15)
          val available = staffMinutesByQuarterHour.get(quarterHour).map { minutes =>
            if (minutes.nonEmpty) minutes.map(_.available).max else 0
          }.getOrElse(0)
          val required = crunchMinutesByQuarterHour.get(quarterHour).map { minutes =>
            val deskRecsByMinute = minutes.groupBy(_.minute).view.mapValues(_.map(_.deskRec).sum).toMap
            val required = (slotStart.millisSinceEpoch until slotEnd.millisSinceEpoch by oneMinuteMillis).map { slotMinute =>
              val deskRec = deskRecsByMinute.getOrElse(slotMinute, 0)
              val fixedPoints = staffByMinute.get(slotMinute).map(minutes => if (minutes.nonEmpty) minutes.map(_.fixedPoints).max else 0).getOrElse(0)
              deskRec + fixedPoints
            }
            if (required.nonEmpty) required.max else 0
          }.getOrElse(0)
          val diff = available - required
          (available.toString, required.toString, diff.toString)
        }
        val headings = (s"$dateFormatted - available", s"$dateFormatted - required", s"$dateFormatted - difference")
        Seq(headings) ++ availableAndRequired
      }
    }

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
