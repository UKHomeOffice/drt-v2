package drt.client.services

import drt.shared.{ApiPaxTypeAndQueueCount, PaxTypes}

case class ChartData(dataSets: Seq[ChartDataSet]) {

}

case class ChartDataSet(title: String, labelValues: Seq[(String, Double)], colour: String = "rgba(52,52,52,0.4)")  {

  def labels: Seq[String] = labelValues.map(_._1)

  def values: Seq[Double] = labelValues.map(_._2)

}

object ChartData {

  def splitToPaxTypeData(splits: Set[ApiPaxTypeAndQueueCount], legend: String = "Passenger Types"): ChartDataSet = ChartDataSet(
    legend,
    splits
      .foldLeft(Map[String, Double]())(
        (acc: Map[String, Double], ptqc: ApiPaxTypeAndQueueCount) => {
          val label = PaxTypes.displayName(ptqc.passengerType)
          acc + (label -> (acc.getOrElse(label, 0.0) + ptqc.paxCount))
        }
      )
      .toSeq
      .sortBy {
        case (paxType, _) => paxType
      })

  def apply(dataSet: ChartDataSet): ChartData = ChartData(List(dataSet))

  def splitToNationalityChartData(splits: Set[ApiPaxTypeAndQueueCount]) =
    ChartDataSet(
      "All Queues",
      splits
        .foldLeft(Map[String, Double]())(
          (acc: Map[String, Double], ptqc: ApiPaxTypeAndQueueCount) => {
            val nationalityCountForSplit = ptqc.nationalities.getOrElse(List()).map {
              case (nat, count) =>
                nat.code -> (acc.getOrElse(nat.code, 0.0) + count)
            }.toMap
            acc ++ nationalityCountForSplit
          }
        )
        .toSeq
        .sortBy {
          case (nat, _) => nat
        })

}

