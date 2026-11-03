package services.crunch.deskrecs

import org.slf4j.{ Logger, LoggerFactory }
import services.metrics.{ Metrics, MetricsCollectorLike }

import scala.util.control.NonFatal

case class QueueAllocationContext(
    requestId: String,
    kind: QueueAllocationKind,
    port: String,
    terminal: String,
    mode: QueueAllocationMode,
    startMillis: Long,
    endMillis: Long
)

trait QueueAllocationReporter {
  def event(
      context: QueueAllocationContext,
      name: String,
      outcome: String,
      fields: Map[String, String] = Map.empty
  ): Unit

  def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit
}

object QueueAllocationReporter {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  val NoOp: QueueAllocationReporter = new QueueAllocationReporter {
    override def event(
        context: QueueAllocationContext,
        name: String,
        outcome: String,
        fields: Map[String, String]
    ): Unit = ()

    override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()
  }

  def safe(delegate: QueueAllocationReporter): QueueAllocationReporter = new QueueAllocationReporter {
    private def report(operation: String)(f: => Unit): Unit =
      try f
      catch {
        case NonFatal(failure) =>
          log.error(
            s"event=queue_allocation_telemetry_failed operation=$operation failure_type=${failure.getClass.getName}"
          )
      }

    override def event(
        context: QueueAllocationContext,
        name: String,
        outcome: String,
        fields: Map[String, String]
    ): Unit = report("event")(delegate.event(context, name, outcome, fields))

    override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit =
      report("duration")(delegate.duration(context, stage, milliseconds))
  }
}

object LoggingQueueAllocationReporter {
  // Metrics is not a MetricsCollectorLike; its timer adds drt., whereas its counter does not.
  private[deskrecs] val defaultMetrics: MetricsCollectorLike = new MetricsCollectorLike {
    override def timer(name: String, milliseconds: Double): Unit = Metrics.timer(name, milliseconds)

    override def counter(name: String, value: Double): Unit = Metrics.counter(name, value)
  }

  private val eventNames: Set[String] = Set(
    "queue_allocation_configured",
    "queue_allocation_ineligible",
    "queue_allocation_cancellation_overrun",
    "queue_allocation_neutral_completed",
    "queue_allocation_fallback_started",
    "queue_allocation_fallback_completed",
    "queue_allocation_request_failed"
  )

  private val outcomes: Set[String] = Set("completed", "failed", "ineligible", "started", "configured")

  private val stages: Set[String] =
    Set("neutral", "fallback", "cancellation", "shutdown")

  private val fieldNames: Set[String] = Set(
    "stage",
    "reason",
    "authoritative_algorithm",
    "fallback",
    "neutral_max_duration_ms",
    "fallback_max_duration_ms",
    "cancel_grace_ms",
    "shutdown_grace_ms",
    "duration_ms",
    "bank_limit_source",
    "replan_count",
    "demand_evaluations",
    "search_limited_plans",
    "minimum_shortfall_queue_minutes",
    "minimum_shortfall_positions",
    "useful_idle_capacity_minutes",
    "max_planning_horizon_minutes",
    "timing_evaluations",
    "timing_adjusted_plans",
    "reference_demand_queues",
    "quality_evaluations",
    "quality_adjusted_plans",
    "reference_shortfall_queue_minutes"
  )

  private def escape(value: String): String =
    value.flatMap {
      case '\\'                                                                    => "\\\\"
      case '"'                                                                     => "\\\""
      case '\n'                                                                    => "\\n"
      case '\r'                                                                    => "\\r"
      case '\t'                                                                    => "\\t"
      case c if Character.isISOControl(c) || Character.isWhitespace(c) || c == '=' =>
        f"\\u${c.toInt}%04x"
      case c => c.toString
    }
}

class LoggingQueueAllocationReporter(
    metrics: MetricsCollectorLike = LoggingQueueAllocationReporter.defaultMetrics
) extends QueueAllocationReporter {
  import LoggingQueueAllocationReporter._

  private val log: Logger = LoggerFactory.getLogger(getClass)

  override def event(
      context: QueueAllocationContext,
      name: String,
      outcome: String,
      fields: Map[String, String]
  ): Unit = {
    require(eventNames.contains(name), "Unsupported queue allocation event")
    val prefix = Vector(
      "event" -> name,
      "schema_version" -> "1",
      "policy" -> "proportional_service_quality_v4",
      "request_id" -> context.requestId,
      "kind" -> context.kind.name,
      "port" -> context.port,
      "terminal" -> context.terminal,
      "mode" -> context.mode.name,
      "start_millis" -> context.startMillis.toString,
      "end_millis" -> context.endMillis.toString,
      "outcome" -> outcome
    )
    val aggregateFields = fields.toVector.filter { case (key, _) => fieldNames.contains(key) }.sortBy(_._1)
    val rejected = fields.size - aggregateFields.size
    if (rejected > 0) {
      log.warn(s"event=queue_allocation_telemetry_fields_rejected count=$rejected")
    }
    val message = (prefix ++ aggregateFields).map { case (key, value) => s"$key=${escape(value)}" }.mkString(" ")
    log.info(message)
    if (
      name == "queue_allocation_cancellation_overrun" ||
      name == "queue_allocation_request_failed" ||
      outcome == "invalid" ||
      (name == "queue_allocation_fallback_completed" && outcome != "completed")
    ) log.error(message)

    val outcomeBucket = if (outcomes.contains(outcome)) outcome else "other"
    metrics.counter(s"drt.queue_allocation.$name.${context.kind.name}.${context.mode.name}.$outcomeBucket", 1d)
  }

  override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = {
    require(stages.contains(stage), "Unsupported queue allocation duration stage")
    require(milliseconds >= 0L, "Queue allocation duration must not be negative")
    metrics.timer(s"queue_allocation.$stage.${context.kind.name}.${context.mode.name}", milliseconds.toDouble)
  }
}
