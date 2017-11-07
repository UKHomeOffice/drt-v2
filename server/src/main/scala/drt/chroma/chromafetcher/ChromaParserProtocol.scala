package drt.chroma.chromafetcher

import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight, ChromaToken}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

trait ChromaParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val chromaTokenFormat = jsonFormat3(ChromaToken)
  implicit val chromaLiveFlightFormat = jsonFormat20(ChromaLiveFlight)
  implicit val chromaForecastFlightFormat = jsonFormat9(ChromaForecastFlight)
}

object ChromaParserProtocol extends ChromaParserProtocol



