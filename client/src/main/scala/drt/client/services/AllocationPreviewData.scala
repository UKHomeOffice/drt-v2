package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.StaffMinute
import drt.shared.AllocationPreview.{ Response, Result }
import drt.shared.PortState
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{ LocalDate, SDateLike }

import scala.collection.immutable.SortedMap

object AllocationPreviewData {
  case class Window(startMillis: Long, endMillis: Long)

  def viewWindow(day: LocalDate, viewStart: SDateLike, hours: Int): Window = {
    val dayStart = SDate(day)
    val start = viewStart.millisSinceEpoch
    val end = if (hours == 24 && start == dayStart.millisSinceEpoch) dayStart.addDays(1).millisSinceEpoch
    else viewStart.addHours(hours).millisSinceEpoch
    Window(start, end)
  }

  def coveredSlots(
      response: Response,
      result: Result,
      terminal: Terminal,
      window: Window,
      interval: Int
  ): Seq[Long] = {
    val queuesCovered = result.minutes.map(m => (m.terminal, m.queue, m.minute)).toSet
    val staffCovered = result.staffMinutes.map(m => (m.terminal, m.minute)).toSet
    val periodMillis = interval * 60000L
    (window.startMillis until window.endMillis by periodMillis).filter { start =>
      val end = start + periodMillis
      start >= response.startMillis && end <= response.endMillis && end <= window.endMillis &&
      result.queues.nonEmpty && (start until end by 60000L).forall { minute =>
        staffCovered.contains((terminal, minute)) &&
        result.queues.forall(queue => queuesCovered.contains((terminal, queue, minute)))
      }
    }
  }

  case class Aggregates(
      windowCrunch: SortedMap[Long, Map[Queue, CrunchMinute]],
      dayCrunch: SortedMap[Long, Map[Queue, CrunchMinute]],
      recommendedStaff: SortedMap[Long, Int],
      availableStaff: SortedMap[Long, Option[Int]],
      staff: Map[Long, StaffMinute]
  )

  def selectedResult(response: Response, available: Boolean): Option[Result] =
    if (available) response.available else response.recommended

  def frozenPortState(response: Response, available: Boolean): Option[PortState] =
    selectedResult(response, available).map { selected =>
      val recByMinute = response.recommended.toSeq.flatMap(_.minutes).map(m => (m.queue, m.minute) -> m).toMap
      val depByMinute = response.available.toSeq.flatMap(_.minutes).map(m => (m.queue, m.minute) -> m).toMap
      val minutes = selected.minutes.map { minute =>
        val rec = recByMinute.get((minute.queue, minute.minute))
        val dep = depByMinute.get((minute.queue, minute.minute))
        minute.copy(
          deskRec = rec.map(_.deskRec).getOrElse(0),
          waitTime = rec.map(_.waitTime).getOrElse(0),
          maybePaxInQueue = rec.flatMap(_.maybePaxInQueue),
          deployedDesks = dep.flatMap(_.deployedDesks),
          deployedWait = dep.flatMap(_.deployedWait),
          maybeDeployedPaxInQueue = dep.flatMap(_.maybeDeployedPaxInQueue),
          actDesks = None,
          actWait = None
        )
      }
      PortState(Seq.empty, minutes, selected.staffMinutes)
    }

  def aggregate(
      state: AllocationPreviewState,
      terminal: Terminal,
      viewStart: SDateLike,
      hours: Int,
      interval: Int,
      available: Boolean = false
  ): Option[Aggregates] = for {
    context <- state.context if state.active
    response <- state.response
    result <- selectedResult(response, available)
    portState <- frozenPortState(response, available)
  } yield {
    val window = viewWindow(context.date, viewStart, hours)
    val dayStart = SDate(context.date)
    val dayWindow = Window(dayStart.millisSinceEpoch, dayStart.addDays(1).millisSinceEpoch)
    val slots = coveredSlots(response, result, terminal, window, interval)
    val chartSlots = coveredSlots(response, result, terminal, dayWindow, interval)
    def companionSlots(companion: Option[Result]): Set[Long] = companion.toSeq.flatMap(r =>
      coveredSlots(response, r.copy(queues = result.queues), terminal, window, interval)
    ).toSet
    val recommendedSlots = companionSlots(response.recommended)
    val availableSlots = companionSlots(response.available)

    def crunchSummary(times: Seq[Long]): SortedMap[Long, Map[Queue, CrunchMinute]] =
      SortedMap.empty[Long, Map[Queue, CrunchMinute]] ++ times.map { time =>
        time -> portState.crunchSummary(SDate(time), 1, interval, terminal, result.queues)(time)
      }

    Aggregates(
      crunchSummary(slots),
      crunchSummary(chartSlots),
      SortedMap.empty[Long, Int] ++ response.recommended.toSeq.flatMap { recommended =>
        val source = PortState(Seq.empty, recommended.minutes, recommended.staffMinutes)
        slots.filter(recommendedSlots.contains).map(time =>
          time -> source.queueRecStaffSummary(SDate(time), 1, interval, terminal, result.queues)(time)
        )
      },
      SortedMap.empty[Long, Option[Int]] ++ response.available.toSeq.flatMap { available =>
        val source = PortState(Seq.empty, available.minutes, available.staffMinutes)
        slots.filter(availableSlots.contains).map(time =>
          time -> source.queueDepStaffSummary(SDate(time), 1, interval, terminal, result.queues)(time)
        )
      },
      slots.map(time => time -> portState.staffSummary(SDate(time), 1, interval, terminal)(time)).toMap
    )
  }

  def queueSize(minute: CrunchMinute, kind: String): Option[Int] =
    if (kind == "available") minute.maybeDeployedPaxInQueue else minute.maybePaxInQueue
}
