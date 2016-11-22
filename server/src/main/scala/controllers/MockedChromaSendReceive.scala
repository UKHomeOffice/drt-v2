package controllers

import http.WithSendAndReceive
import org.slf4j.LoggerFactory
import spray.http._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait MockedChromaSendReceive extends WithSendAndReceive {
  private val log = LoggerFactory.getLogger(getClass)
  val mockStream = getClass.getClassLoader.getResourceAsStream("edi-chroma.json")
  val content = scala.io.Source.fromInputStream(mockStream).getLines().mkString("\n")
  override def sendAndReceive = {
    def res(req: HttpRequest): Future[HttpResponse] = Future {
      log.info(s"mocked request is ${req}")
      req.uri.path match {
        case Uri.Path(chromaTokenPath) if chromaTokenPath.contains("/chroma/token") => {
          HttpResponse().withEntity(
            HttpEntity(ContentTypes.`application/json`,
              """{"access_token":"LIk79Cj6NLssRcWePFxkJMIhpmSbe5gBGqOOxNIuxWNVd7JWsWtoOqAZDnM5zADvkbdIJ0BHkJgaya2pYyu8yH2qb8zwXA4TxZ0Jq0JwhgqulMgcv1ottnrUA1U61pu1TNFN5Bm08nvqZpYtwCWfGNGbxdrol-leZry_UD8tgxyZLfj45rgzmxm2u2DBN8TFpB_uG6Pb1B2XHM3py6HgYAmqSTjTK060PyNWTp_czsU",
                |"token_type":"bearer","expires_in":86399}""".stripMargin))
        }
        case Uri.Path(chromaPayh) if chromaPayh.contains("/chroma/live/") => {
          HttpResponse(StatusCodes.OK,
            HttpEntity(ContentTypes.`application/json`,
              content
            ))
        }
      }
    }
    res
  }
}
