package actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import spray.caching.{Cache, LruCache}

import scala.concurrent.duration._

case class CachableActorQuery(props: Props, query: Any)

class CachingCrunchReadActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher
  implicit val timeout = Timeout(2 seconds)

  def receive: Receive = {
    case caq: CachableActorQuery =>
      val actorName = "query-actor" + UUID.randomUUID().toString
      val s = sender()
      val readActor: AskableActorRef = context.actorOf(caq.props, actorName)
      resultCache(key(caq)) {
        readActor.ask(caq.query)
      }.pipeTo(s)
  }

  val resultCache: Cache[Any] = LruCache()

  def key(query: CachableActorQuery): Int = {
    query.hashCode()
  }
}

