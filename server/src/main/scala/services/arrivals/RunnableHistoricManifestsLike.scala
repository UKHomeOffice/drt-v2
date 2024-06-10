package services.arrivals

import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream._
import akka.{Done, NotUsed}
import uk.gov.homeoffice.drt.arrivals.UniqueArrival

trait RunnableHistoricManifestsLike {
  protected def constructAndRunGraph(flow: Flow[Iterable[UniqueArrival], Done, NotUsed])
                                    (implicit mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val completionMatcher: PartialFunction[Any, CompletionStrategy] = {
      case Done => CompletionStrategy.immediately
    }
    Source.actorRef[Iterable[UniqueArrival]](
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
