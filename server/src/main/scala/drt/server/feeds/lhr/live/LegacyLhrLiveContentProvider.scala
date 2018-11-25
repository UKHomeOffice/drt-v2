package drt.server.feeds.lhr.live

import com.typesafe.config.ConfigFactory

import scala.util.Try

case class LegacyLhrLiveContentProvider() {
  def csvContentsProviderProd(): Try[String] = {
    Try(Seq(
      "/usr/local/bin/lhr-live-fetch-latest-feed.sh",
      "-u", ConfigFactory.load.getString("feeds.lhr.live.username"),
      "-p", ConfigFactory.load.getString("feeds.lhr.live.password")).!!)
  }
}
