package drt.chroma

import com.typesafe.config.ConfigFactory
import spray.http.FormData

trait ChromaConfig {
  lazy val config = ConfigFactory.load()

  val chromaTokenRequestCredentials = FormData(Seq(
    "username" -> config.getString("chromausername"),
    "password" -> config.getString("chromapassword"),
    "grant_type" -> "password"
  ))
  val tokenUrl: String = config.getString("chromatokenurl")
  val url: String = config.getString("chromaliveurl")
}
