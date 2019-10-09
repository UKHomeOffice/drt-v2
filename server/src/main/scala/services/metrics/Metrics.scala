package services.metrics

import com.typesafe.config.{Config, ConfigFactory}
import github.gphat.censorinus.StatsDClient

object Metrics {
  private val config: Config = ConfigFactory.load()
  val portCode: String = config.getString("portcode")
  val statsd: StatsDClient = new StatsDClient()

  def timer(name: String, milliseconds: Double): Unit = statsd.timer(s"$portCode-$name", milliseconds = milliseconds)
  def increment(name: String, value: Double = 1d): Unit = statsd.increment(s"$portCode-$name", value = value)
}
