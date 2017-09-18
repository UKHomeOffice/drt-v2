package actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import spray.caching.{Cache, LruCache}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

case class CachableActorQuery(props: Props, query: Any)

class CachingCrunchReadActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher
  implicit val timeout = Timeout(5 seconds)

  def receive: Receive = {
    case caq: CachableActorQuery =>
      log.info(s"Received query: $caq")
      val actorName = "query-actor" + UUID.randomUUID().toString
      val s = sender()
      val readActor: AskableActorRef = context.actorOf(caq.props, actorName)
      resultCache(key(caq)) {
        val resToCache: Future[Any] = readActor.ask(caq.query)
        val res = Await.result(resToCache, 5 seconds)
        log.info(s"The result of the thing: $res")
        res
      }.pipeTo(s)
  }

  val resultCache: Cache[Any] = LruCache()

  def key(query: CachableActorQuery): Int = {
    query.hashCode()
  }
}

