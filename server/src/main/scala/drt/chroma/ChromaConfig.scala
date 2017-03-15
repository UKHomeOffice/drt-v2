package drt.chroma

import com.typesafe.config.ConfigFactory
import spray.http.FormData

trait ChromaConfig {
  lazy val config = ConfigFactory.load()

  val chromaTokenRequestCredentials = FormData(Seq(
    "username" -> config.getString("chroma.username"),
    "password" -> config.getString("chroma.password"),
    "grant_type" -> "password"
  ))
  val tokenUrl: String = config.getString("chroma.url.token")
  val url: String = config.getString("chroma.url.live")
}
