package actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import spray.caching.{Cache, LruCache}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

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

      val hit = resultCache.keys.contains(key(caq))
      if(hit) {

        log.info(s"Cache hit: ${caq.props}")
      } else {
        log.info(s"Cache miss: ${caq.props}")

      }
      resultCache(key(caq)) {

        log.info(s"Starting actor for query ${caq.props}")
        val actorRef: ActorRef = context.actorOf(caq.props, actorName)
        val askableActorRef: AskableActorRef = actorRef
        val resToCache: Future[Any] = askableActorRef.ask(caq.query)
        val res = Await.result(resToCache, 5 seconds)
        log.info(s"Sending and caching: ${caq.props}")
        actorRef ! PoisonPill
        res
      }.pipeTo(s)
  }

  val resultCache: Cache[Any] = LruCache(maxCapacity = 50)

  def key(query: CachableActorQuery): Int = {
    query.hashCode()
  }
}

