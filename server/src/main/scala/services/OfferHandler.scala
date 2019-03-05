package services

import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object OfferHandler {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def offerWithRetries[A](queue: SourceQueueWithComplete[A], thingToOffer: A, retries: Int): Unit = {
    val resultFuture = queue.offer(thingToOffer).map {
      case Enqueued => true
      case failure =>
        log.warn(s"Failed to enqueue ${thingToOffer.getClass}. $retries retries left. Result was: $failure")
        false
    }.recover {
      case t =>
        log.warn(s"Failed to enqueue ${thingToOffer.getClass}. $retries retries left", t)
        false
    }

    val offerSuccess = Try(Await.result(resultFuture, 5 seconds)) match {
      case Success(result) => result
      case Failure(t) => false
    }

    if (!offerSuccess) {
      if (retries > 0) {
        log.info(s"Waiting before retrying")
        Thread.sleep(5000)
        offerWithRetries(queue, thingToOffer, retries - 1)
      }
      else log.error(s"Failed to enqueue ${thingToOffer.getClass}. No retries left")
    }
  }

}
