package drt.http

import akka.actor.ActorSystem
import spray.http.{HttpResponse, HttpRequest}
import spray.client.pipelining._
import scala.concurrent.Future
import scala.concurrent.duration._

trait WithSendAndReceive {
 // sendAndReceive gives us a position where we can mock out interaction
  def sendAndReceive:  (HttpRequest) => Future[HttpResponse]
}

trait ProdSendAndReceive extends WithSendAndReceive {
  implicit val system: ActorSystem

  override def sendAndReceive: SendReceive = sendReceive(system, system.dispatcher, 60.seconds)
}

