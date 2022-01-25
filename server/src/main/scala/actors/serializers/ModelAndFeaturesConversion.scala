package actors.serializers

import drt.shared.CrunchApi.MillisSinceEpoch
import server.protobuf.messages.ModelAndFeatures.{FeaturesMessage, ModelAndFeaturesMessage, OneToManyFeatureMessage, RegressionModelMessage}
import uk.gov.homeoffice.drt.prediction.FeatureType.{OneToMany, Single}
import uk.gov.homeoffice.drt.prediction.{FeatureType, Features, ModelAndFeatures, RegressionModel}
import uk.gov.homeoffice.drt.time.SDateLike

//case class RegressionModel(coefficients: Iterable[Double], intercept: Double)

//trait ModelAndFeatures {
//  val model: RegressionModel
//  val features: Features
//  val examplesTrainedOn: Int
//  val improvementPct: Double
//}

//object ModelAndFeatures {
//  def apply(model: RegressionModel,
//            features: Features,
//            targetName: String,
//            examplesTrainedOn: Int,
//            improvementPct: Double,
//            sDateProvider: MillisSinceEpoch => SDateLike,
//           ): ModelAndFeatures = targetName match {
//    case TouchdownModelAndFeatures.targetName => TouchdownModelAndFeatures(model, features, examplesTrainedOn, improvementPct, sDateProvider)
//  }
//}

//object TouchdownModelAndFeatures {
//  val targetName: String = "touchdown"
//}

//case class TouchdownModelAndFeatures(model: RegressionModel, features: Features, examplesTrainedOn: Int, improvementPct: Double, sDateProvider: MillisSinceEpoch => SDateLike) extends ModelAndFeatures {
//  def maybePrediction(arrival: Arrival): Option[Long] = {
//    val dow = s"dow_${sDateProvider(arrival.Scheduled).getDayOfWeek()}"
//    val partOfDay = s"pod_${sDateProvider(arrival.Scheduled).getHours() / 12}"
//    val dowIdx = features.oneToManyValues.indexOf(dow)
//    val partOfDayIds = features.oneToManyValues.indexOf(partOfDay)
//    for {
//      dowCo <- model.coefficients.toIndexedSeq.lift(dowIdx)
//      partOfDayCo <- model.coefficients.toIndexedSeq.lift(partOfDayIds)
//    } yield {
//      val offScheduled = (model.intercept + dowCo + partOfDayCo).toInt
//      arrival.Scheduled + (offScheduled * MilliTimes.oneMinuteMillis)
//    }
//  }
//}

//object FeatureType {
//  sealed trait FeatureType
//
//  case class Single(columnName: String) extends FeatureType
//
//  case class OneToMany(columnNames: List[String], featurePrefix: String) extends FeatureType
//}

//case class Features(featureTypes: List[FeatureType], oneToManyValues: IndexedSeq[String]) {
//  def oneToManyFeatures: Seq[OneToMany] =
//    featureTypes.collect {
//      case otm: OneToMany => otm
//    }
//
//  def singleFeatures: Seq[Single] =
//    featureTypes.collect {
//      case otm: Single => otm
//    }
//}

object ModelAndFeaturesConversion {
  def modelAndFeaturesFromMessage(msg: ModelAndFeaturesMessage, sDateProvider: MillisSinceEpoch => SDateLike): ModelAndFeatures = {
    val model = msg.model.map(modelFromMessage).getOrElse(throw new Exception("No value for model"))
    val features = msg.features.map(featuresFromMessage).getOrElse(throw new Exception("No value for features"))
    val targetName = msg.targetName.getOrElse(throw new Exception("Mandatory parameter 'targetName' not specified"))
    val examplesTrainedOn = msg.examplesTrainedOn.getOrElse(throw new Exception("No value for examplesTrainedOn"))
    val improvement = msg.improvementPct.getOrElse(throw new Exception("No value for improvement"))

    ModelAndFeatures(model, features, targetName, examplesTrainedOn, improvement, sDateProvider)
  }

  def modelFromMessage(msg: RegressionModelMessage): RegressionModel =
    RegressionModel(msg.coefficients, msg.intercept.getOrElse(throw new Exception("No value for intercept")))

  def featuresFromMessage(msg: FeaturesMessage): Features = {
    val singles: Seq[Single] = msg.singleFeatures.map(Single)
    val oneToManys: Seq[OneToMany] = msg.oneToManyFeatures.map(oneToManyFromMessage)
    val allFeatures: Seq[FeatureType.FeatureType] = oneToManys ++ singles
    Features(allFeatures.toList, msg.oneToManyValues.toIndexedSeq)
  }

  def oneToManyFromMessage(msg: OneToManyFeatureMessage): OneToMany =
    OneToMany(msg.columns.toList, msg.prefix.getOrElse(throw new Exception("No value for prefix")))
}
