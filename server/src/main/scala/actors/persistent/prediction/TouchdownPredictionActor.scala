package actors.persistent.prediction

import actors.persistent.{RecoveryActorLike, Sizes}
import actors.persistent.staffing.GetState
import actors.serializers.ModelAndFeatures
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.ModelAndFeatures.ModelAndFeaturesMessage

class TouchdownPredictionActor(val now: () => SDateLike,
                               terminal: String,
                               number: Int,
                               origin: String
                              ) extends RecoveryActorLike {
  import actors.serializers.ModelAndFeaturesConversion._

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val recoveryStartMillis: Long = now().millisSinceEpoch

  var state: Option[ModelAndFeatures] = None

  override def persistenceId: String = s"touchdown-prediction-$terminal-$number-$origin".toLowerCase

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case msg: ModelAndFeaturesMessage =>
      log.info(s"recovering state from ModelAndFeaturesMessage")
      state = Option(modelAndFeaturesFromMessage(msg))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case msg: ModelAndFeaturesMessage =>
      state = Option(modelAndFeaturesFromMessage(msg))
  }

  override def stateToMessage: GeneratedMessage = throw new Exception(s"Persistence not supported here")

  override def receiveCommand: Receive = {
    case GetState =>
      log.info(s"Received request for $terminal-$number-$origin model and features")
      sender ! state
  }

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None
}
