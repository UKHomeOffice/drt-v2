package services.metrics

import akka.stream.{Inlet, Outlet}
import com.typesafe.config.ConfigFactory
import drt.shared.CrunchApi.MillisSinceEpoch
import github.gphat.censorinus.StatsDClient
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

trait MetricsCollectorLike {
  def timer(name: String, milliseconds: Double): Unit
}

class StatsDMetrics extends MetricsCollectorLike {
  val statsd: StatsDClient = new StatsDClient()
  override def timer(name: String, milliseconds: Double): Unit = statsd.timer(name, milliseconds)
}

class LoggingMetrics extends MetricsCollectorLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  override def timer(name: String, milliseconds: Double): Unit = log.info(s"$name took ${milliseconds}ms")
}

object Metrics {
  val collector: MetricsCollectorLike = if (ConfigFactory.load().getBoolean("enable-statsd")) new StatsDMetrics else new LoggingMetrics

  private def appPrefix = s"drt"

  private def prependAppName(name: String): String = s"$appPrefix-$name"

  def timer(name: String, milliseconds: Double): Unit = {
    val fullName = prependAppName(name)
    collector.timer(fullName, milliseconds = milliseconds)
  }

  def graphStageTimer(stageName: String, inletOutletName: String, milliseconds: Double): Unit = timer(s"graphstage-$stageName-$inletOutletName", milliseconds = milliseconds)
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
