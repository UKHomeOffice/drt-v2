package drt.shared

import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.{ macroRW, ReadWriter }

object AllocationPreview {
  case class Request(terminal: Terminal, date: LocalDate, kind: String = "recommended")
  object Request {
    implicit val rw: ReadWriter[Request] = macroRW
  }

  case class Measurements(
      slaBreachMinutes: Long,
      breachPassengerMinutes: Long,
      queuePassengerMinutes: Long,
      staffMinutes: Long
  )
  object Measurements {
    implicit val rw: ReadWriter[Measurements] = macroRW
  }

  case class MeasurementSummary(
      byQueue: Map[Queue, Measurements],
      total: Option[Measurements],
      unavailableQueues: Seq[Queue],
      resourceFeasible: Boolean
  )
  object MeasurementSummary {
    implicit val rw: ReadWriter[MeasurementSummary] = macroRW
  }

  case class MeasurementComparison(legacy: MeasurementSummary, neutral: MeasurementSummary)
  object MeasurementComparison {
    implicit val rw: ReadWriter[MeasurementComparison] = macroRW
  }

  case class Result(
      queues: Seq[Queue],
      minutes: Seq[CrunchMinute],
      staffMinutes: Seq[CrunchApi.StaffMinute],
      slas: Map[Queue, Int],
      measurements: Option[MeasurementComparison] = None
  )
  object Result {
    implicit val rw: ReadWriter[Result] = macroRW
  }

  case class Response(
      startMillis: Long,
      endMillis: Long,
      recommended: Option[Result],
      available: Option[Result]
  )
  object Response {
    implicit val rw: ReadWriter[Response] = macroRW
  }
}
