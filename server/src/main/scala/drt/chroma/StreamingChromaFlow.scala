package drt.chroma

import akka.NotUsed
import akka.actor.Cancellable
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import drt.chroma.chromafetcher.{ChromaFetcherForecast, ChromaFetcherLive}
import drt.chroma.chromafetcher.ChromaFetcherLive.{ChromaForecastFlight, ChromaSingleFlight}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.language.postfixOps

object StreamingChromaFlow {

  def chromaPollingSourceLive(log: LoggingAdapter, chromaFetcher: ChromaFetcherLive, pollFrequency: FiniteDuration): Source[Seq[ChromaSingleFlight], Cancellable] = {
    implicit val l = log
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Try[Seq[ChromaSingleFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => Try(chromaFetcher.currentFlightsBlocking))

    log.info(s"setting up ticking chroma source")
    val recoverableTicking: Source[Seq[ChromaSingleFlight], Cancellable] = tickingSource
      .map {
        case x@Failure(f) =>
          log.error(f, s"Something went wrong on the fetch, but we'll try again in $pollFrequency")
          x
        case s => s
      }
      .collect({
        case Success(s: Seq[ChromaSingleFlight]) =>
          log.info("Got success {} flights", s.length)
          s
      })
    recoverableTicking
  }

  def chromaPollingSourceForecast(log: LoggingAdapter, chromaFetcher: ChromaFetcherForecast, pollFrequency: FiniteDuration): Source[Seq[ChromaForecastFlight], Cancellable] = {
    implicit val l = log
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Try[Seq[ChromaForecastFlight]], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => Try(chromaFetcher.currentFlightsBlocking))

    log.info(s"setting up ticking chroma source")
    val recoverableTicking: Source[Seq[ChromaForecastFlight], Cancellable] = tickingSource
      .map {
        case x@Failure(f) =>
          log.error(f, s"Something went wrong on the fetch, but we'll try again in $pollFrequency")
          x
        case s => s
      }
      .collect({
        case Success(s: Seq[ChromaForecastFlight]) =>
          log.info("Got success {} flights", s.length)
          s
      })
    recoverableTicking
  }
}
