package drt.chroma

import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import com.typesafe.config.{Config, ConfigFactory}
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFlightMarshallers}
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaLiveFlight, ChromaToken}
import drt.http.WithSendAndReceive

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.reflectiveCalls

class MockChromaConnectorSpec extends AkkaStreamTestKitSpecificationLike {
  test =>
  val log: LoggingAdapter = system.log

  val mockConfig: Config = ConfigFactory.parseMap(
    Map(
      "chroma.url.live" -> "http://someserver/somepath",
      "chroma.url.token" -> "http://someserve/someotherpath",
      "chroma.username" -> "magicuser",
      "chroma.password" -> "pass"
    )
  )

  import system.dispatcher

  "When we request a chroma token, if it returns success for token and result we parse successfully" >> {
    val sut = new ChromaFetcher(ChromaLive, ChromaFlightMarshallers.live) with WithSendAndReceive {
      override lazy val config: Config = mockConfig
      private val pipeline = tokenPipeline _

      def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => Future {
        HttpResponse().withEntity(
          HttpEntity(ContentTypes.`application/json`,
            """{"access_token":"LIk79Cj6NLssRcWePFxkJMIhpmSbe5gBGqOOxNIuxWNVd7JWsWtoOqAZDnM5zADvkbdIJ0BHkJgaya2pYyu8yH2qb8zwXA4TxZ0Jq0JwhgqulMgcv1ottnrUA1U61pu1TNFN5Bm08nvqZpYtwCWfGNGbxdrol-leZry_UD8tgxyZLfj45rgzmxm2u2DBN8TFpB_uG6Pb1B2XHM3py6HgYAmqSTjTK060PyNWTp_czsU",
              |"token_type":"bearer","expires_in":86399}""".stripMargin))
      }

      val response: Future[ChromaToken] = {
        pipeline(HttpRequest(method = HttpMethods.POST, uri = tokenUrl, entity = chromaTokenRequestCredentials.toEntity))
      }

      def await = Await.result(response, 10 seconds) must equalTo(ChromaToken(
        "LIk79Cj6NLssRcWePFxkJMIhpmSbe5gBGqOOxNIuxWNVd7JWsWtoOqAZDnM5zADvkbdIJ0BHkJgaya2pYyu8yH2qb8zwXA4TxZ0Jq0JwhgqulMgcv1ottnrUA1U61pu1TNFN5Bm08nvqZpYtwCWfGNGbxdrol-leZry_UD8tgxyZLfj45rgzmxm2u2DBN8TFpB_uG6Pb1B2XHM3py6HgYAmqSTjTK060PyNWTp_czsU",
        "bearer", 86399))
    }
    sut.await
  }

  "When we request current flights we parse them successfully" >> {
    val sut = new ChromaFetcher(ChromaLive, ChromaFlightMarshallers.live) with WithSendAndReceive {
      override lazy val config: Config = mockConfig
      override val tokenUrl: String = "https://edibf.edinburghairport.com/edi/chroma/token"
      override val url: String = "https://edibf.edinburghairport.com/edi/chroma/live/edi"
      private val pipeline: Future[Seq[ChromaLiveFlight]] = currentFlights

      def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => Future {
        req.uri.path match {
          case Uri.Path("/edi/chroma/token") => {
            HttpResponse().withEntity(
              HttpEntity(ContentTypes.`application/json`,
                """{"access_token":"LIk79Cj6NLssRcWePFxkJMIhpmSbe5gBGqOOxNIuxWNVd7JWsWtoOqAZDnM5zADvkbdIJ0BHkJgaya2pYyu8yH2qb8zwXA4TxZ0Jq0JwhgqulMgcv1ottnrUA1U61pu1TNFN5Bm08nvqZpYtwCWfGNGbxdrol-leZry_UD8tgxyZLfj45rgzmxm2u2DBN8TFpB_uG6Pb1B2XHM3py6HgYAmqSTjTK060PyNWTp_czsU",
                  |"token_type":"bearer","expires_in":86399}""".stripMargin))
          }
          case Uri.Path("/edi/chroma/live/edi") =>
            HttpResponse(status = StatusCodes.OK,
              entity = HttpEntity(ContentTypes.`application/json`,
                """
                  |[
                  |  {
                  |    "Operator": "Tnt Airways Sa",
                  |    "Status": "On Chocks",
                  |    "EstDT": "2016-08-04T04:40:00Z",
                  |    "ActDT": "2016-08-04T04:37:00Z",
                  |    "EstChoxDT": "",
                  |    "ActChoxDT": "2016-08-04T04:53:00Z",
                  |    "Gate": "",
                  |    "Stand": "207",
                  |    "MaxPax": 0,
                  |    "ActPax": 0,
                  |    "TranPax": 0,
                  |    "RunwayID": "24",
                  |    "BaggageReclaimId": "",
                  |    "FlightID": 1200980,
                  |    "AirportID": "EDI",
                  |    "Terminal": "FRT",
                  |    "ICAO": "TAY025N",
                  |    "IATA": "3V025N",
                  |    "Origin": "LGG",
                  |    "SchDT": "2016-08-04T04:35:00Z"
                  |  },
                  |  {
                  |    "Operator": "Star Air",
                  |    "Status": "On Chocks",
                  |    "EstDT": "",
                  |    "ActDT": "2016-08-04T05:32:00Z",
                  |    "EstChoxDT": "",
                  |    "ActChoxDT": "2016-08-04T05:41:00Z",
                  |    "Gate": "",
                  |    "Stand": "212",
                  |    "MaxPax": 0,
                  |    "ActPax": 0,
                  |    "TranPax": 0,
                  |    "RunwayID": "24",
                  |    "BaggageReclaimId": "",
                  |    "FlightID": 1200986,
                  |    "AirportID": "EDI",
                  |    "Terminal": "FRT",
                  |    "ICAO": "SRR6566",
                  |    "IATA": "S66566",
                  |    "Origin": "CGN",
                  |    "SchDT": "2016-08-04T05:15:00Z"
                  |  }
                  |  ]
                """.stripMargin))
        }
      }

      val response: Future[Seq[ChromaLiveFlight]] = {
        pipeline
      }

      def await = Await.result(response, 10 seconds) must equalTo(Seq(
        SampleData.flight1,
        SampleData.flight2
      ))
    }
    sut.await
  }
}
