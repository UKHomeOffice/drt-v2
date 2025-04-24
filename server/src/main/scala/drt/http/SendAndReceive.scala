package drt.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

trait WithSendAndReceive {
  type SendReceive = HttpRequest => Future[HttpResponse]
 // sendAndReceive gives us a position where we can mock out interaction
  def sendAndReceive: SendReceive
}

trait ProdSendAndReceive extends WithSendAndReceive {
  implicit val system: ActorSystem

  override def sendAndReceive: SendReceive = request => Http()(system).singleRequest(request)
}

