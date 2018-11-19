package filters

import akka.stream.Materializer
import javax.inject.{Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.mvc.{Filter, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

@Singleton
class NoCacheFilter @Inject()(
                               implicit val config: Configuration,
                               implicit override val mat: Materializer,
                               exec: ExecutionContext) extends Filter {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val rootRegex: Regex = config.get[String]("play.http.context").r

  override def apply(requestHeaderToFutureResult: RequestHeader => Future[Result])
                    (rh: RequestHeader): Future[Result] = {
    requestHeaderToFutureResult(rh).map { result =>
      rh.uri match {
        case rootRegex() =>
          result.withHeaders(HeaderNames.CACHE_CONTROL -> "no-cache")
        case _ =>
          result
      }
    }
  }
}
