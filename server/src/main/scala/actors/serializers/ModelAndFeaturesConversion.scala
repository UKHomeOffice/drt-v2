package actors.serializers

import uk.gov.homeoffice.drt.protobuf.messages.ModelAndFeatures.{FeaturesMessage, ModelAndFeaturesMessage, OneToManyFeatureMessage, RegressionModelMessage}
import uk.gov.homeoffice.drt.prediction.Feature.{OneToMany, Single}
import uk.gov.homeoffice.drt.prediction.{Feature, FeaturesWithOneToManyValues, ModelAndFeatures, RegressionModel}


object ModelAndFeaturesConversion {
  def modelAndFeaturesFromMessage(msg: ModelAndFeaturesMessage): ModelAndFeatures = {
    val model = msg.model.map(modelFromMessage).getOrElse(throw new Exception("No value for model"))
    val features = msg.features.map(featuresFromMessage).getOrElse(throw new Exception("No value for features"))
    val targetName = msg.targetName.getOrElse(throw new Exception("Mandatory parameter 'targetName' not specified"))
    val examplesTrainedOn = msg.examplesTrainedOn.getOrElse(throw new Exception("No value for examplesTrainedOn"))
    val improvementPct = msg.improvementPct.getOrElse(0D)

    ModelAndFeatures(model, features, targetName, examplesTrainedOn, improvementPct)
  }

  def modelFromMessage(msg: RegressionModelMessage): RegressionModel =
    RegressionModel(msg.coefficients, msg.intercept.getOrElse(throw new Exception("No value for intercept")))

  def featuresFromMessage(msg: FeaturesMessage): FeaturesWithOneToManyValues = {
    val singles: Seq[Single] = msg.singleFeatures.map(Single)
    val oneToManys: Seq[OneToMany] = msg.oneToManyFeatures.map(oneToManyFromMessage)
    val allFeatures: Seq[Feature] = oneToManys ++ singles
    FeaturesWithOneToManyValues(allFeatures.toList, msg.oneToManyValues.toIndexedSeq)
  }

  def oneToManyFromMessage(msg: OneToManyFeatureMessage): OneToMany =
    OneToMany(msg.columns.toList, msg.prefix.getOrElse(throw new Exception("No value for prefix")))
}
