package queueus

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

case class DynamicQueueStatusProvider(queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
                                      maxDesks: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                      egateUpdates: PortEgateBanksUpdates
                                     ) {
  def queueStatus: Terminal => (Queue, MillisSinceEpoch) => QueueStatus =
    terminal => (queue, millis) => {
      if (queuesForDateAndTerminal(SDate(millis).toLocalDate, terminal).contains(queue)) {
        if (queue == EGate)
          egateStatus(millis, egateUpdates, terminal)
        else
          deskStatus(maxDesks, millis, terminal, queue)
      } else Closed
    }

  private def egateStatus(time: MillisSinceEpoch,
                          egatePortUpdates: PortEgateBanksUpdates,
                          terminal: Terminal): QueueStatus =
    egatePortUpdates.updatesByTerminal
      .get(terminal)
      .map { updates =>
        if (updates.forTime(time).exists(!_.isClosed)) Open else Closed
      }
      .getOrElse(Closed)

  private def deskStatus(maxDesks: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                         time: MillisSinceEpoch,
                         terminal: Terminal,
                         queue: Queue): QueueStatus =
    maxDesks.get(terminal) match {
      case None => Closed
      case Some(queuesMaxByHour) =>
        queuesMaxByHour.get(queue) match {
          case None => Closed
          case Some(maxByHour) =>
            if (maxByHour.isEmpty) Closed
            else {
              val hour = SDate(time, europeLondonTimeZone).getHours
              maxByHour.lift(hour) match {
                case None => Closed
                case Some(0) => Closed
                case Some(_) => Open
              }
            }
        }
    }
}

