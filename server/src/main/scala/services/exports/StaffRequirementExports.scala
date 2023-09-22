package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.CrunchMinute
import services.LocalDateStream
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

object StaffRequirementExport {
  private val relevantMinute: (LocalDate, Seq[CrunchMinute]) => Seq[CrunchMinute] =
    (current, minutes) => minutes.filter { m => SDate(m.minute).toLocalDate == current }

  def queuesProvider(utcQueuesProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed],
                    ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, Seq[CrunchMinute]), NotUsed] =
    LocalDateStream(utcQueuesProvider, startBufferDays = 0, endBufferDays = 0, transformData = relevantMinute)

  def toDailyHeadlines(queues: Seq[Queue]): (LocalDate, Seq[CrunchMinute]) => Seq[String] =
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
}
