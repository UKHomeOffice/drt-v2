package services

import scala.concurrent.{ExecutionContext, Future}

object SourceUtils {
  def reduceFutureIterables[T, I](iterable: Iterable[I], reducer: Iterable[T] => T)
                                 (implicit ec: ExecutionContext): (I => Future[T]) => Future[T] =
    (eventualForIterable: I => Future[T]) =>
      Future
        .sequence(iterable.map(eventualForIterable))
        .map { x =>
          println(s"Reducing iterable of size ${x.size}")
          reducer(x)
        }
}
