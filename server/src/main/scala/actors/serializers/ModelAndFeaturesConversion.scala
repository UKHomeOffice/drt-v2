package actors.serializers

import actors.serializers.FeatureType.{FeatureType, OneToMany, Single}
import server.protobuf.messages.ModelAndFeatures.{FeaturesMessage, ModelAndFeaturesMessage, OneToManyFeatureMessage, RegressionModelMessage}

case class RegressionModel(coefficients: Iterable[Double], intercept: Double)

case class ModelAndFeatures(model: RegressionModel, features: Features)

object FeatureType {
  sealed trait FeatureType

  case class Single(columnName: String) extends FeatureType

  case class OneToMany(columnNames: List[String], featurePrefix: String) extends FeatureType
}

case class Features(featureTypes: List[FeatureType], oneToManyValues: IndexedSeq[String]) {
  def oneToManyFeatures: Seq[OneToMany] =
    featureTypes.collect {
      case otm: OneToMany => otm
    }

  def singleFeatures: Seq[Single] =
    featureTypes.collect {
      case otm: Single => otm
    }
}

object ModelAndFeaturesConversion {
  def modelAndFeaturesFromMessage(msg: ModelAndFeaturesMessage): ModelAndFeatures = {
    val model = msg.model.map(modelFromMessage).getOrElse(throw new Exception("No value for model"))
    val features = msg.features.map(featuresFromMessage).getOrElse(throw new Exception("No value for features"))

    ModelAndFeatures(model, features)
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

//  def modelToMessage(model: RegressionModel): RegressionModelMessage =
//    RegressionModelMessage(
//      coefficients = model.coefficients.toArray,
//      intercept = Option(model.intercept),
//    )
//
//  def featuresToMessage(features: Features): FeaturesMessage = {
//    FeaturesMessage(
//      oneToManyFeatures = features.featureTypes.collect {
//        case OneToMany(columnNames, featurePrefix) =>
//          OneToManyFeatureMessage(columnNames, Option(featurePrefix))
//      },
//      singleFeatures = features.featureTypes.collect {
//        case Single(columnName) => columnName
//      },
//      oneToManyValues = features.oneToManyValues
//    )
//  }
//
//  def modelAndFeaturesToMessage(modelAndFeatures: ModelAndFeatures, now: Long): ModelAndFeaturesMessage = {
//    ModelAndFeaturesMessage(
//      model = Option(modelToMessage(modelAndFeatures.model)),
//      features = Option(featuresToMessage(modelAndFeatures.features)),
//      timestamp = Option(now),
//    )
//  }
}
