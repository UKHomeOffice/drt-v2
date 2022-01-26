//package actors.serializers
//
//import drt.shared.CrunchApi.MillisSinceEpoch
//import server.protobuf.messages.ModelAndFeatures.{FeaturesMessage, ModelAndFeaturesMessage, OneToManyFeatureMessage, RegressionModelMessage}
//import uk.gov.homeoffice.drt.prediction.FeatureType.{OneToMany, Single}
//import uk.gov.homeoffice.drt.prediction.{FeatureType, Features, ModelAndFeatures, RegressionModel}
//import uk.gov.homeoffice.drt.time.SDateLike
//
//
//object ModelAndFeaturesConversion {
//  def modelAndFeaturesFromMessage(msg: ModelAndFeaturesMessage, sDateProvider: MillisSinceEpoch => SDateLike): ModelAndFeatures = {
//    val model = msg.model.map(modelFromMessage).getOrElse(throw new Exception("No value for model"))
//    val features = msg.features.map(featuresFromMessage).getOrElse(throw new Exception("No value for features"))
//    val targetName = msg.targetName.getOrElse(throw new Exception("Mandatory parameter 'targetName' not specified"))
//    val examplesTrainedOn = msg.examplesTrainedOn.getOrElse(throw new Exception("No value for examplesTrainedOn"))
//    val improvement = msg.improvementPct.getOrElse(throw new Exception("No value for improvement"))
//
//    ModelAndFeatures(model, features, targetName, examplesTrainedOn, improvement, sDateProvider)
//  }
//
//  def modelFromMessage(msg: RegressionModelMessage): RegressionModel =
//    RegressionModel(msg.coefficients, msg.intercept.getOrElse(throw new Exception("No value for intercept")))
//
//  def featuresFromMessage(msg: FeaturesMessage): Features = {
//    val singles: Seq[Single] = msg.singleFeatures.map(Single)
//    val oneToManys: Seq[OneToMany] = msg.oneToManyFeatures.map(oneToManyFromMessage)
//    val allFeatures: Seq[FeatureType.FeatureType] = oneToManys ++ singles
//    Features(allFeatures.toList, msg.oneToManyValues.toIndexedSeq)
//  }
//
//  def oneToManyFromMessage(msg: OneToManyFeatureMessage): OneToMany =
//    OneToMany(msg.columns.toList, msg.prefix.getOrElse(throw new Exception("No value for prefix")))
//}
