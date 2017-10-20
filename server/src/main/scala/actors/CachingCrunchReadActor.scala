package actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import services.SDate
import spray.caching.{Cache, LruCache}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class CachableActorQuery(props: Props, query: Any)

class CachingCrunchReadActor extends Actor with ActorLogging {

  import context.dispatcher

  implicit val timeout = Timeout(60 seconds)

  def receive: Receive = {
    case caq: CachableActorQuery =>
      log.info(s"Received query: $caq")
      val replyTo = sender()
      val cacheKey = key(caq)

      val hit = resultCache.keys.contains(cacheKey)
      if (hit) {
        log.info(s"Cache hit: $cacheKey - ${caq.props}")
      } else {
        log.info(s"Cache miss: $cacheKey - ${caq.props}")
      }
      val cachedResult = resultCache(cacheKey) {

        val start = SDate.now().millisSinceEpoch
        val actorName = "query-actor" + UUID.randomUUID().toString

        log.info(s"Starting actor for query ${caq.props}")
        val actorRef: ActorRef = context.actorOf(caq.props, actorName)
        val askableActorRef: AskableActorRef = actorRef
        val resToCache: Future[Any] = askableActorRef.ask(caq.query)
        resToCache.onComplete {
          case Success(s) =>
            log.info(s"Res from actor succeeded")
          case Failure(f) =>
            log.info(s"Res from actor failed: $f")
        }

        log.info(s"Sent query to actor, waiting for response")

        log.info(s"Sending and caching: with key $cacheKey -  ${caq.props}")
        val res = Await.result(resToCache, 60 seconds)
        log.info(s"Cache generation took ${(SDate.now().millisSinceEpoch - start) / 1000} seconds")
        actorRef ! PoisonPill
        res
      }
      cachedResult.onComplete {
        case Success(s) =>
          log.info(s"Succeeded to get result from Cache")
          replyTo ! s
        case Failure(f) =>
          log.info(s"Failed to get result from Cache: $f")
          replyTo ! f
      }
  }

  val resultCache: Cache[Any] = LruCache(maxCapacity = 50)

  def key(query: CachableActorQuery): Int = {
    query.hashCode()
  }
}

