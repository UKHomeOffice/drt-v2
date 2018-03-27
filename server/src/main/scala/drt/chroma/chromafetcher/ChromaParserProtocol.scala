package drt.chroma.chromafetcher

import drt.chroma.chromafetcher.ChromaFetcher.{AcsToken, ChromaForecastFlight, ChromaLiveFlight, ChromaToken}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

trait ChromaParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val acsTokenFormat = jsonFormat4(AcsToken)
  implicit val chromaTokenFormat = jsonFormat3(ChromaToken)
  implicit val chromaLiveFlightFormat = jsonFormat20(ChromaLiveFlight)
  implicit val chromaForecastFlightFormat = jsonFormat9(ChromaForecastFlight)
}

object ChromaParserProtocol extends ChromaParserProtocol



