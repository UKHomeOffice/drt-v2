package services

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

object SourceUtils {
  def applyFutureIterablesReducer[I1, I2, T](iterable1: Iterable[I1],
                                             eventualForIterables: I1 => I2 => Future[T],
                                             futureReducer: (I2 => Future[T]) => Future[T])
                                            (implicit ec: ExecutionContext): Source[T, NotUsed] =
    Source(iterable1.toList)
      .mapAsync(1)(d => futureReducer(eventualForIterables(d)))

  def reduceFutureIterables[T, I](iterable: Iterable[I], reducer: Iterable[T] => T)
                                 (implicit ec: ExecutionContext): (I => Future[T]) => Future[T] =
    (eventualForIterable: I => Future[T]) =>
      Future
        .sequence(iterable.map(eventualForIterable))
        .map(reducer)
}
