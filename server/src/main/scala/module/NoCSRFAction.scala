package module

import play.api.mvc.{ActionBuilderImpl, BodyParsers, Request, Result}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class NoCSRFAction @Inject()(parser: BodyParsers.Default)(implicit ec: ExecutionContext)
  extends ActionBuilderImpl(parser) {
  override def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    block(request).map(_.withHeaders("Csrf-Token" -> "nocheck"))
  }
}
