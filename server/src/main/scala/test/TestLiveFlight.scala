package test

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import test.TestFlight.{TestForecastFlight, TestLiveFlight}

import scala.concurrent.Future


trait TestFlightParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val testFlightTokenFormat: RootJsonFormat[TestFlightToken] = jsonFormat3(TestFlightToken)
  implicit val testLiveFlightFormat: RootJsonFormat[TestLiveFlight] = jsonFormat22(TestLiveFlight)
  implicit val testForecastFlightFormat: RootJsonFormat[TestForecastFlight] = jsonFormat9(TestForecastFlight)
}

object TestFlightParserProtocol extends TestFlightParserProtocol

case class TestFlightToken(access_token: String, token_type: String, expires_in: Int)

object TestFlight {

  sealed trait TestFlightLike

  case class TestLiveFlight(Operator: String,
                            Status: String,
                            EstDT: String,
                            ActDT: String,
                            EstChoxDT: String,
                            ActChoxDT: String,
                            Gate: String,
                            Stand: String,
                            MaxPax: Int,
                            ActPax: Int,
                            TranPax: Int,
                            RunwayID: String,
                            BaggageReclaimId: String,
                            FlightID: Int,
                            AirportID: String,
                            Terminal: String,
                            ICAO: String,
                            IATA: String,
                            Origin: String,
                            SchDT: String,
                            ServiceType: String,
                            LoadFactor: Double) extends TestFlightLike

  case class TestForecastFlight(
                                 EstPax: Int,
                                 EstTranPax: Int,
                                 FlightID: Int,
                                 AirportID: String,
                                 Terminal: String,
                                 ICAO: String,
                                 IATA: String,
                                 Origin: String,
                                 SchDT: String) extends TestFlightLike

}


object TestFlightMarshallers {

  import TestFlightParserProtocol._

  def live(implicit mat: Materializer): HttpResponse => Future[List[TestLiveFlight]] = r => Unmarshal(r).to[List[TestLiveFlight]]

  def forecast(implicit mat: Materializer): HttpResponse => Future[List[TestForecastFlight]] = r => Unmarshal(r).to[List[TestForecastFlight]]
}