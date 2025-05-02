package services.arrivals

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.{Done, NotUsed}

trait RunnableUniqueArrivalsLike {
  protected def constructAndRunGraph[T](flow: Flow[T, Done, NotUsed])
                                    (implicit mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val completionMatcher: PartialFunction[Any, CompletionStrategy] = {
      case Done => CompletionStrategy.immediately
    }
    Source.actorRef[T](
        completionMatcher = completionMatcher,
        failureMatcher = PartialFunction.empty,
        bufferSize = 365,
        overflowStrategy = OverflowStrategy.dropTail,
      )
      .via(flow)
      .viaMat(KillSwitches.single)(Keep.both)
      .to(Sink.ignore)
      .run()
  }
}
