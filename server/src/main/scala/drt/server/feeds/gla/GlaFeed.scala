package drt.server.feeds.gla

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import drt.server.feeds.common.ProdHttpClient
import drt.server.feeds.{ArrivalsFeedResponse, AzinqFeed, Feed}

import scala.concurrent.ExecutionContext

object GlaFeed {
  def apply(url: String, username: String, password: String, token: String)
           (implicit ec: ExecutionContext, mat: Materializer, system: ActorSystem): Source[ArrivalsFeedResponse, ActorRef[Feed.FeedTick]] = {
    import drt.server.feeds.gla.AzinqGlaArrivalJsonFormats._
    val fetchArrivals = AzinqFeed(url, username, password, token, ProdHttpClient().sendRequest)
    AzinqFeed.source(Feed.actorRefSource, fetchArrivals)
  }
}
