package drt.chroma.chromafetcher

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight, ChromaToken}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait ChromaParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val chromaTokenFormat: RootJsonFormat[ChromaToken] = jsonFormat3(ChromaToken)
  implicit val chromaLiveFlightFormat: RootJsonFormat[ChromaLiveFlight] = jsonFormat20(ChromaLiveFlight)
  implicit val chromaForecastFlightFormat: RootJsonFormat[ChromaForecastFlight] = jsonFormat9(ChromaForecastFlight)
}

object ChromaParserProtocol extends ChromaParserProtocol



