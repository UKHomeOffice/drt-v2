package actors.persistent

import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.time.SDateLike

class FlightPassengerLoadsActor(uniqueArrival: UniqueArrival)(implicit val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = ???

  override def processSnapshotMessage: PartialFunction[Any, Unit] = ???

  override def stateToMessage: GeneratedMessage = ???

  override def receiveCommand: Receive = ???

  override def persistenceId: String = ???
}
