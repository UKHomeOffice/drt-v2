package actors.persistent.prediction

import actors.persistent.staffing.GetState
import actors.persistent.{RecoveryActorLike, Sizes}
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.prediction.ModelAndFeatures
import uk.gov.homeoffice.drt.time.SDateLike
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.ModelAndFeatures.ModelAndFeaturesMessage
import services.SDate
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

class TouchdownPredictionActor(val now: () => SDateLike,
                               terminal: Terminal,
                               number: VoyageNumber,
                               origin: PortCode
                              ) extends RecoveryActorLike {
  import actors.serializers.ModelAndFeaturesConversion._

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val recoveryStartMillis: Long = now().millisSinceEpoch

  var state: Option[ModelAndFeatures] = None

  val uniqueId = s"${terminal.toString}-${number.numeric}-${origin.iata}"

  override def persistenceId: String = s"touchdown-prediction-$uniqueId".toLowerCase

  val sDateProvider: MillisSinceEpoch => SDateLike = (millis: MillisSinceEpoch) => SDate(millis)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case msg: ModelAndFeaturesMessage =>
      log.info(s"recovering state from ModelAndFeaturesMessage")
      state = Option(modelAndFeaturesFromMessage(msg, sDateProvider))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case msg: ModelAndFeaturesMessage =>
      state = Option(modelAndFeaturesFromMessage(msg, sDateProvider))
  }

  override def stateToMessage: GeneratedMessage = throw new Exception(s"Persistence not supported here")

  override def receiveCommand: Receive = {
    case GetState =>
      log.info(s"Received request for $uniqueId model and features")
      sender ! state
  }

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None
}
