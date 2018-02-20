package services.prediction

import org.apache.spark.sql.{DataFrame, SparkSession}


trait SplitsPredictorFactoryLike {
  def predictor(flightCodes: Set[String]): SparkSplitsPredictor
}

case class SparkSplitsPredictorFactory(sparkSession: SparkSession, rawSplitsPath: String, portCode: String)
  extends SplitsPredictorFactoryLike {
  val splitsView: DataFrame = sparkSession
    .read
    .option("header", "true")
    .option("inferSchema", "true")
    .csv(rawSplitsPath)

  splitsView.createOrReplaceTempView("splits")

  def predictor(flightCodes: Set[String]): SparkSplitsPredictor =
    SparkSplitsPredictor(sparkSession, portCode, flightCodes, splitsView)
}