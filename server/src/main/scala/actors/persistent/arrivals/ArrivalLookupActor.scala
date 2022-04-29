package actors.persistent.arrivals

import actors.persistent.Sizes
import actors.persistent.staffing.GetState
import actors.serializers.FlightMessageConversion
import actors.serializers.FlightMessageConversion.{feedStatusesFromSnapshotMessage, restoreArrivalsFromSnapshot}
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.{FeedSourceArrival, FeedSourceStatuses}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2, T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FlightMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap

object ArrivalLookupActor {
  def props(portCode: PortCode, pointInTime: SDateLike, arrivalToLookup: UniqueArrival, persistenceId: String, feedSource: FeedSource): Props = Props(
    new ArrivalLookupActor(portCode, pointInTime, arrivalToLookup, persistenceId, feedSource)
  )
}

class ArrivalLookupActor(portCode: PortCode, pointInTime: SDateLike, arrivalToLookup: UniqueArrival, persistenceIdString: String, feedSource: FeedSource)
  extends ArrivalsActor(() => pointInTime, Int.MaxValue, feedSource) {
  override def persistenceId: String = persistenceIdString

  def now: () => SDateLike = () => pointInTime

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(1000)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = {
    diffsMessage.updates.foreach { msg =>
      val maybeArrivalMsg = maybeMatchingArrivalMessage(msg, arrivalToLookup)

      maybeArrivalMsg.foreach(_ => addArrival(msg))
    }
  }

  private def maybeMatchingArrivalMessage(msg: FlightMessage, toLookup: UniqueArrival): Option[FlightMessage] = {
    val flightNumber = msg.iATA.getOrElse("") match {
      case Arrival.flightCodeRegex(_, numeric, _) => numeric.toInt
      case _ => -1
    }
    val origin = PortCode(msg.origin.getOrElse(""))
    val scheduled = msg.scheduled.getOrElse(0L)
    val terminal = msg.terminal.getOrElse("")
    val ua = UniqueArrival(flightNumber, Terminal(terminal), scheduled, origin)

    if (portCode.iata.toLowerCase == "edi")
      List(A1, A2, T1).find(t => toLookup.copy(terminal = t) == ua).map(_ => msg)
    else if (ua == arrivalToLookup)
      Option(msg)
    else
      None
  }

  private def addArrival(msg: FlightMessage): Unit = {
    val arrival = FlightMessageConversion.flightMessageToApiFlight(msg)
    state = state.copy(arrivals = state.arrivals.updated(arrival.unique, arrival))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      stateMessage.flightMessages.foreach { msg =>
        maybeMatchingArrivalMessage(msg, arrivalToLookup).foreach(addArrival)
      }
      logRecoveryMessage(s"Restored state to snapshot. Arrival ${if (state.arrivals.isEmpty) "not" else ""} found")
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsDiffMessage(Some(createdMillis), _, _, _) =>
      if (createdMillis <= pointInTime.millisSinceEpoch) consumeDiffsMessage(diff)
    case _ =>
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    Recovery(fromSnapshot = criteria, replayMax = 10000)
  }

  override def postRecoveryComplete(): Unit = {
    log.info(s"Recovered ${state.arrivals.size} arrivals for ${state.feedSource}")
  }

  override def receiveCommand: Receive = {
    case GetState =>
      log.info(s"Received GetState request. Sending ArrivalsState with ${state.arrivals.size} arrivals")
      sender() ! state.arrivals.values.headOption.map(a => FeedSourceArrival(feedSource, a))

    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

}
