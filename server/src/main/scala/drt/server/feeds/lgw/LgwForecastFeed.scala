package drt.server.feeds.lgw

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Source
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed}
import drt.shared.FlightsApi.Flights

object LgwForecastFeed {
  def apply()(implicit system: ActorSystem): Source[ArrivalsFeedResponse, ActorRef[Feed.FeedTick]] = {
    val username = system.settings.config.getString("feeds.lgw.forecast.sftp.username")
    val password = system.settings.config.getString("feeds.lgw.forecast.sftp.password")
    val host = system.settings.config.getString("feeds.lgw.forecast.sftp.host")
    val pathPrefix = system.settings.config.getString("feeds.lgw.forecast.sftp.pathPrefix")

    val sftpService = LgwForecastSftpService(host, username, password, pathPrefix)
    val csvParser = LgwForecastFeedCsvParser(sftpService.latestContent)

    val feedSource = Feed.actorRefSource
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
