package actors

import akka.actor.Actor
import drt.server.feeds.{GetFeedImportArrivals, StoreFeedImportArrivals}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}

class ArrivalsImportActor() extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var maybeArrivalsFromImport: Option[Flights] = None

  override def receive: Receive = {
    case StoreFeedImportArrivals(incomingArrivals) =>
      log.info(s"Storing arrivals from import")
      maybeArrivalsFromImport = Option(incomingArrivals)

    case GetFeedImportArrivals =>
      log.info(s"Sending arrivals from import")
      sender() ! maybeArrivalsFromImport
      if (maybeArrivalsFromImport.nonEmpty) maybeArrivalsFromImport = None
  }
}
