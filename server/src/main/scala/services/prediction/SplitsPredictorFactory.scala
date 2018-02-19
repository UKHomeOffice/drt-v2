package services.prediction

import org.apache.spark.sql.{DataFrame, SparkSession}


trait SplitsPredictorFactoryLike {
  def predictor(flightCodes: Set[String]): SplitsPredictor
}

case class SparkSplitsPredictorFactory(sparkSession: SparkSession, rawSplitsPath: String, portCode: String)
  extends SplitsPredictorFactoryLike {
  val splitsView: DataFrame = sparkSession
    .read
    .option("header", "true")
    .option("inferSchema", "true")
    .csv(rawSplitsPath)

  def predictor(flightCodes: Set[String]): SplitsPredictor =
    SplitsPredictor(sparkSession, portCode, flightCodes, splitsView)
}