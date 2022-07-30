package drt.client.services.charts

import drt.client.components.ChartJSComponent.ChartJsData
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxTypes}

case class ChartData(dataSets: Seq[ChartDataSet])

case class ChartDataSet(
                         title: String,
                         labelValues: Seq[(String, Double)],
                         colour: String = "rgba(52,52,52,0.4)") {

  def labels: Seq[String] = labelValues.map(_._1)

  def values: Seq[Double] = labelValues.map(_._2)

}

object ChartData {

  def splitToPaxTypeData(splits: Set[ApiPaxTypeAndQueueCount], legend: String = "Passenger Types"): ChartJsData = {
    val data = splits
      .foldLeft(Map[String, Double]())(
        (acc: Map[String, Double], ptqc: ApiPaxTypeAndQueueCount) => {
          val label = PaxTypes.displayName(ptqc.passengerType)
          acc + (label -> (acc.getOrElse(label, 0.0) + ptqc.paxCount))
        }
      )
      .toSeq
      .sortBy {
        case (paxType, _) => paxType
      }
    ChartJsData(data.map(_._1), data.map(_._2.round.toDouble), legend)
  }

  def applySplitsToTotal(splitData: Seq[(String, Double)], flightPax: Int): Seq[(String, Double)] = {
    val total = splitData.map(_._2).sum
    splitData.map {
      case (split, pax) =>
        (split, Math.round((pax / total) * flightPax).toDouble)
    }
  }

}

