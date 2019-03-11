package services

import akka.actor.Scheduler
import akka.pattern.after
import akka.stream.scaladsl.SourceQueueWithComplete
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object OfferHandler {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def offerWithRetries[A](queue: SourceQueueWithComplete[A], thingToOffer: A, retries: Int): Unit = {
    retry(queue.offer(thingToOffer), RetryDelays.fibonacci, retries, 5 seconds).onFailure {
      case throwable =>
        log.error(s"Failed to enqueue ${thingToOffer.getClass} after . $retries", throwable)
    }
  }

  def retry[T](futureToRetry: => Future[T], delay: Seq[FiniteDuration], retries: Int, defaultDelay: FiniteDuration )(implicit ec: ExecutionContext, s: Scheduler): Future[T] = futureToRetry
    .recoverWith {
      case _ if retries > 0 =>
        log.warn(s"Future failed. $retries retries remaining")
        after(delay.headOption.getOrElse(defaultDelay), s)(retry(futureToRetry, delay.tail, retries - 1 , defaultDelay))
    }
}

object RetryDelays {
  val fibonacci: Stream[FiniteDuration] = 0.seconds #:: 1.seconds #:: (fibonacci zip fibonacci.tail).map{ t => t._1 + t._2 }
}
