package services.metrics

import akka.stream.{Inlet, Outlet}
import com.typesafe.config.ConfigFactory
import drt.shared.CrunchApi.MillisSinceEpoch
import github.gphat.censorinus.StatsDClient
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

trait MetricsCollectorLike {
  def timer(name: String, milliseconds: Double): Unit
  def counter(name: String, value: Double): Unit
}

class StatsDMetrics extends MetricsCollectorLike {
  val statsd: StatsDClient = new StatsDClient()
  override def timer(name: String, milliseconds: Double): Unit = statsd.timer(name, milliseconds)
  override def counter(name: String, value: Double): Unit = statsd.counter(name, value)
}

class LoggingMetrics extends MetricsCollectorLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  override def timer(name: String, milliseconds: Double): Unit = log.info(s"$name took ${milliseconds}ms")
  override def counter(name: String, value: Double): Unit = log.info(s"$name count: $value")
}

object Metrics {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val collector: MetricsCollectorLike = if (ConfigFactory.load().getBoolean("enable-statsd")) {
    log.info(s"Using statsd for metrics collection")
    new StatsDMetrics
  } else {
    log.info(s"Using logs for metrics collection")
    new LoggingMetrics
  }

  private def appPrefix = s"drt"

  private def prependAppName(name: String): String = s"$appPrefix.$name"

  def timer(name: String, milliseconds: Double): Unit = {
    val fullName = prependAppName(name)
    collector.timer(fullName, milliseconds = milliseconds)
  }

  def graphStageTimer(stageName: String, inletOutletName: String, milliseconds: Double): Unit = {
    timer(s"graphstage.$stageName", milliseconds = milliseconds)
    timer(s"graphstage.$stageName.$inletOutletName", milliseconds = milliseconds)
  }

  def counter(name: String, value: Double): Unit = collector.counter(name, value)
}

case class StageTimer(stageName: String, portName: String, startTime: MillisSinceEpoch) {
  def stopAndReport(): Unit = {
    Metrics.graphStageTimer(stageName = stageName, inletOutletName = portName, milliseconds = SDate.now().millisSinceEpoch - startTime)
  }
}

object StageTimer {
  def apply[T](stageName: String, inlet: Inlet[T]): StageTimer = StageTimer(stageName, inlet.toString().replaceAll("\\.in\\(.*", ""), SDate.now().millisSinceEpoch)
  def apply[T](stageName: String, outlet: Outlet[T]): StageTimer = StageTimer(stageName, outlet.toString().replaceAll("\\.out\\(.*", ""), SDate.now().millisSinceEpoch)
}
