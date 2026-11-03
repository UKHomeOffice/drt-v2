package services.crunch.deskrecs

import ch.qos.logback.classic.{ Level, Logger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import services.metrics.MetricsCollectorLike

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

class QueueAllocationReporterSpec extends AnyWordSpec with Matchers {
  private class Metrics extends MetricsCollectorLike {
    val counters: ListBuffer[(String, Double)] = ListBuffer.empty
    val timings: ListBuffer[(String, Double)] = ListBuffer.empty
    override def counter(name: String, value: Double): Unit = { counters += name -> value; () }
    override def timer(name: String, milliseconds: Double): Unit = { timings += name -> milliseconds; () }
  }

  private val context = QueueAllocationContext(
    "request",
    QueueAllocationKind.Deployments,
    "LHR",
    "T2",
    QueueAllocationMode.Neutral,
    0L,
    60000L
  )

  private def withLog[A](run: (LoggingQueueAllocationReporter, Metrics, ListAppender[ILoggingEvent]) => A): A = {
    val logger = LoggerFactory.getLogger(classOf[LoggingQueueAllocationReporter]) match {
      case logback: Logger => logback
      case _               => fail("Tests require the existing Logback logging backend")
    }
    val original = logger.getLevel
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.setLevel(Level.INFO)
    logger.addAppender(appender)
    try {
      val metrics = new Metrics
      run(new LoggingQueueAllocationReporter(metrics), metrics, appender)
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(original)
      appender.stop()
    }
  }

  "Queue allocation reporting" should {
    "emit searchable INFO events and preserve operational policy diagnostics" in withLog { (reporter, metrics, log) =>
      val fields = Map(
        "bank_limit_source" -> "date_effective_schedule",
        "replan_count" -> "3",
        "demand_evaluations" -> "12",
        "search_limited_plans" -> "1",
        "minimum_shortfall_queue_minutes" -> "2",
        "minimum_shortfall_positions" -> "4",
        "useful_idle_capacity_minutes" -> "5",
        "max_planning_horizon_minutes" -> "90",
        "timing_evaluations" -> "8",
        "timing_adjusted_plans" -> "2",
        "reference_demand_queues" -> "3",
        "quality_evaluations" -> "10",
        "quality_adjusted_plans" -> "2",
        "reference_shortfall_queue_minutes" -> "0",
        "fallback" -> "false"
      )
      reporter.event(context, "queue_allocation_neutral_completed", "completed", fields)
      val message = log.list.asScala.find(_.getLevel == Level.INFO).get.getFormattedMessage
      message should include("event=queue_allocation_neutral_completed")
      message should include("mode=neutral")
      message should include("policy=proportional_service_quality_v4")
      fields.foreach { case (key, value) => message should include(s"$key=$value") }
      metrics.counters.map(_._1).toSeq shouldBe Seq(
        "drt.queue_allocation.queue_allocation_neutral_completed.deployments.neutral.completed"
      )
    }

    "escape newlines, whitespace and separators without logging unknown passenger fields" in withLog {
      (reporter, _, log) =>
        reporter.event(
          context.copy(requestId = "request\ninjected=event"),
          "queue_allocation_request_failed",
          "failed",
          Map("reason" -> "input failed\nstage=other", "passenger_loads" -> "DO_NOT_LOG")
        )
        val messages = log.list.asScala.map(_.getFormattedMessage).toSeq
        val message = messages.find(_.startsWith("event=queue_allocation_request_failed")).get
        message should not include "\n"
        message should include("request_id=request\\ninjected\\u003devent")
        message should include("reason=input\\u0020failed\\nstage\\u003dother")
        messages.mkString should not include "DO_NOT_LOG"
        messages should contain("event=queue_allocation_telemetry_fields_rejected count=1")
    }

    "keep request-specific values out of metric names and map unknown outcomes to one bounded bucket" in withLog {
      (reporter, metrics, _) =>
        Seq("first request", "another-window").foreach { request =>
          reporter.event(
            context.copy(requestId = request),
            "queue_allocation_request_failed",
            s"unexpected-$request",
            Map("reason" -> request)
          )
        }
        metrics.counters.map(_._1).distinct.toSeq shouldBe Seq(
          "drt.queue_allocation.queue_allocation_request_failed.deployments.neutral.other"
        )
        reporter.duration(context, "neutral", 12L)
        metrics.timings.toSeq shouldBe Seq("queue_allocation.neutral.deployments.neutral" -> 12d)
    }

    "reject unsupported event and stage names" in withLog { (reporter, _, _) =>
      intercept[IllegalArgumentException](reporter.event(context, "arbitrary", "failed"))
      intercept[IllegalArgumentException](reporter.duration(context, "arbitrary", 1))
      intercept[IllegalArgumentException](reporter.duration(context, "neutral", -1))
    }

    "isolate reporter failures from authoritative work" in {
      val broken = new QueueAllocationReporter {
        override def event(
            ctx: QueueAllocationContext,
            name: String,
            outcome: String,
            fields: Map[String, String]
        ): Unit = throw new IllegalStateException("event failed")
        override def duration(ctx: QueueAllocationContext, stage: String, milliseconds: Long): Unit =
          throw new IllegalStateException("timer failed")
      }
      noException should be thrownBy QueueAllocationReporter.safe(broken)
        .event(context, "queue_allocation_request_failed", "failed")
      noException should be thrownBy QueueAllocationReporter.safe(broken).duration(context, "neutral", 1)
    }
  }
}
