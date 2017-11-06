package drt.chroma

import com.typesafe.config.ConfigFactory
import spray.http.FormData

trait FeedType {
  def toString: String
}
case object LiveFeed extends FeedType {
  override def toString: String = "live"
}
case object ForecastFeed extends FeedType {
  override def toString: String = "forecast"
}

trait ChromaConfig {
  lazy val config = ConfigFactory.load()
  def feedType: FeedType

  val chromaTokenRequestCredentials = FormData(Seq(
    "username" -> config.getString("chroma.username"),
    "password" -> config.getString("chroma.password"),
    "grant_type" -> "password"
  ))
  val tokenUrl: String = config.getString("chroma.url.token")
  val url: String = config.getString(s"chroma.url.$feedType")
}
