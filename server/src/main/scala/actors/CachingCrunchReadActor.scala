package actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings, LfuCacheSettings}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import services.SDate
import services.metrics.Metrics

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class CachableActorQuery(props: Props, query: Any)

class CachingCrunchReadActor extends Actor with ActorLogging {

  import context.dispatcher

  implicit val timeout: Timeout = Timeout(60 seconds)

  val defaultCachingSettings = CachingSettings(context.system)
  val lfuCacheSettings: LfuCacheSettings = defaultCachingSettings.lfuCacheSettings
    .withInitialCapacity(25)
    .withMaxCapacity(50)
    .withTimeToLive(20.seconds)
    .withTimeToIdle(10.seconds)
  val cachingSettings: CachingSettings = defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings)
  val resultCache: Cache[Int, Any] = LfuCache(cachingSettings)

  def receive: Receive = {
    case caq: CachableActorQuery =>
      log.info(s"Received query: $caq")
      val replyTo = sender()
      val cacheKey: Int = key(caq)

      val hit = resultCache.keys.contains(cacheKey)
      if (hit) {
        log.info(s"Cache hit: $cacheKey - ${caq.props}")
      } else {
        log.info(s"Cache miss: $cacheKey - ${caq.props}")
      }

      val cachedResult = resultCache.getOrLoad(cacheKey, key => {
        val start = SDate.now().millisSinceEpoch
        val actorName = "query-actor" + UUID.randomUUID().toString

        log.info(s"Starting actor for query ${caq.props}")
        val actorRef: ActorRef = context.actorOf(caq.props, actorName)
        val askableActorRef: AskableActorRef = actorRef
        val resToCache: Future[Any] = askableActorRef.ask(caq.query)

        resToCache.onComplete {
          case Success(_) =>
            actorRef ! PoisonPill
            log.info(s"Res from actor succeeded")
            Metrics.timer("caching-actor-cache-generation", SDate.now().millisSinceEpoch - start)

          case Failure(f) =>
            actorRef ! PoisonPill
            log.info(s"Res from actor failed: $f")
        }

        log.info(s"Sent query to actor, waiting for response")
        log.info(s"Sending and caching: with key $key -  ${caq.props}")

        resToCache
      })

      cachedResult.onComplete {
        case Success(s) =>
          log.info(s"Succeeded to get result from Cache")
          replyTo ! s
        case Failure(f) =>
          log.info(s"Failed to get result from Cache: $f")
          replyTo ! f
      }
  }

  def key(query: CachableActorQuery): Int = {
    query.hashCode()
  }
}
