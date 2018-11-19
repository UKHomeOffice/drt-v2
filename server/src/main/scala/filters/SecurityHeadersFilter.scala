package filters

import akka.stream.Materializer
import drt.shared.{LoggedInUser, Role, Roles}
import javax.inject.{Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class SecurityHeadersFilter @Inject()(
                                       implicit override val mat: Materializer,
                                       exec: ExecutionContext) extends Filter {

  override def apply(requestHeaderToFutureResult: RequestHeader => Future[Result])
                    (rh: RequestHeader): Future[Result] = requestHeaderToFutureResult(rh)
    .map(_.withHeaders("X-Frame-Options" -> "deny"))
}

