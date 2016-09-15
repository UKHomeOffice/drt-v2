//package drt.chroma.chromafetcher
//
//import drt.chroma.chromafetcher.ChromaFetcher.{ChromaSingleFlight, ChromaToken}
//import spray.httpx.SprayJsonSupport
//import spray.json.DefaultJsonProtocol
//
//trait ChromaParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
//  implicit val chromaTokenFormat = jsonFormat3(ChromaToken)
//  implicit val chromaSingleFlightFormat = jsonFormat20(ChromaSingleFlight)
//}
//
//object ChromaParserProtocol extends ChromaParserProtocol
//
//
//
