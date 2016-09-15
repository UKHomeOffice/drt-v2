//package drt.chroma
//
//trait ChromaConfig {
//  lazy val config = ConfigFactory.load()
//
//  val chromaTokenRequestCredentials = FormData(Seq(
//    "username" -> config.getString("chromausername"),
//    "password" -> config.getString("chromapassword"),
//    "grant_type" -> "password"
//  ))
//  val tokenUrl: String = config.getString("chromatokenurl")
//  val url: String = config.getString("chromaliveurl")
//}
