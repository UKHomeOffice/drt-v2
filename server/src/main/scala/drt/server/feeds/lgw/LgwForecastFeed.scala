package drt.server.feeds.lgw

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.scaladsl.Source
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed}

object LgwForecastFeed {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  def apply()(implicit system: ActorSystem): Source[ArrivalsFeedResponse, ActorRef[Feed.FeedTick]] = {
    val config = system.settings.config
    val username = config.getString("feeds.lgw.forecast.sftp.username")
    val password = config.getString("feeds.lgw.forecast.sftp.password")
    val host = config.getString("feeds.lgw.forecast.sftp.host")
    val port = config.getInt("feeds.lgw.forecast.sftp.port")
    val pathPrefix = config.getString("feeds.lgw.forecast.sftp.pathPrefix")

    log.info(s"LGW Forecast feed: $host:$port/$pathPrefix username: $username")

    val sftpService = LgwForecastSftpService(host, port, username, password, pathPrefix)
    val csvParser = LgwForecastFeedCsvParser(sftpService.latestContent)

    val feedSource = Feed.actorRefSource
      .map { _ =>
        log.info("Tick - Fetching LGW forecast feed")
        csvParser.parseLatestContent() match {
          case Some(arrivals) =>
            ArrivalsFeedSuccess(arrivals)
          case None =>
            ArrivalsFeedFailure("Failed to fetch LGW forecast feed")
        }
      }

    feedSource
  }
}
