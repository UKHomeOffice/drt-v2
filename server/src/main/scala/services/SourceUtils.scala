package services

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.math.Ordering

object SourceUtils {
  def flatMapIterableStreams[I1, I2, T, S](iterable1: Iterable[I1],
                                           iterable2: Iterable[I2],
                                           eventualSourceForIterables: (I1, I2) => Future[Source[T, NotUsed]],
                                           transformFunc: immutable.Seq[T] => immutable.Seq[T])
                                          (implicit mat: Materializer, ec: ExecutionContext): Source[T, NotUsed] = {
    Source(iterable1.toList)
      .mapAsync(1) { d =>
        Future.sequence(iterable2.map(eventualSourceForIterables(d, _)))
          .flatMap { x: Iterable[Source[T, NotUsed]] =>
            Source(x.to[immutable.Iterable])
              .flatMapConcat(identity)
              .runWith(Sink.seq).map(transformFunc)
          }
      }
      .mapConcat(identity)
  }
}
