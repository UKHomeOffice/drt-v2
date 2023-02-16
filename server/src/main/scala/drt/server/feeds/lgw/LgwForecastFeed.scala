package drt.server.feeds.lgw

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.FlightsApi.Flights

import scala.concurrent.duration.FiniteDuration

object LgwForecastFeed {
  def apply(interval: FiniteDuration, initialDelay: FiniteDuration)(implicit system: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {
    val username = system.settings.config.getString("feeds.lgw.forecast.sftp.username")
    val password = system.settings.config.getString("feeds.lgw.forecast.sftp.password")
    val host = system.settings.config.getString("feeds.lgw.forecast.sftp.host")
    val pathPrefix = system.settings.config.getString("feeds.lgw.forecast.sftp.pathPrefix")

    val sftpService = LgwForecastSftpService(host, username, password, pathPrefix)
    val csvParser = LgwForecastFeedCsvParser(sftpService.latestContent)

    val feedSource = Source.tick(initialDelay, interval, NotUsed)
      .map { _ =>
        csvParser.parseLatestFile() match {
          case Some(flights) =>
            val arrivals = flights.map(_.asArrival)
            ArrivalsFeedSuccess(Flights(arrivals))
          case None =>
            ArrivalsFeedFailure("Failed to fetch LGW forecast feed")
        }
      }

    feedSource
  }
}
