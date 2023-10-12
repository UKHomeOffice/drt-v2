package actors

import akka.actor.Actor
import akka.persistence.SaveSnapshotFailure
import drt.server.feeds.{GetFeedImportArrivals, StoreFeedImportArrivals}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted

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

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message ${unexpected.getClass}")
  }
}
