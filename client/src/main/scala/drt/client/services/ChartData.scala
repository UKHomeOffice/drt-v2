package drt.client.services

import drt.shared.{ApiPaxTypeAndQueueCount, PaxAge, PaxTypes}

case class ChartData(dataSets: Seq[ChartDataSet]) {

}

case class ChartDataSet(
                         title: String,
                         labelValues: Seq[(String, Double)],
                         colour: String = "rgba(52,52,52,0.4)") {

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

  case class AgeRange(bottom: Int, top: Option[Int]) {
    def isInRange(age: Int) = this match {
      case AgeRange(bottom, Some(top)) => age >= bottom && age <= top
      case AgeRange(bottom, None) => age > bottom
    }

    def title: String = top match {
      case Some(top) => s"$bottom-$top"
      case _ => s">$bottom"
    }
  }

  object AgeRange {
    def apply(bottom: Int, top: Int): AgeRange = AgeRange(bottom, Option(top))

    def apply(bottom: Int): AgeRange = AgeRange(bottom, None)
  }

  def splitDataToAgeRanges(splits: Set[ApiPaxTypeAndQueueCount]) = {
    val ageRanges = List(
      AgeRange(0, 11),
      AgeRange(12, 24),
      AgeRange(25, 49),
      AgeRange(50, 65),
      AgeRange(65),
    )

    val data = ageRanges.map(range => {
      val ageCount: Seq[(PaxAge, Double)] = splits.toList.flatMap(_.ages.getOrElse(Map()))
      val totalInAgeRange: Double = ageCount
        .collect {
          case (age, count) if range.isInRange(age.years) => count
        }
        .sum
      (range.title, totalInAgeRange)
    })
    ChartDataSet(
      "Passenger Ages",
      data)
  }

  def applySplitsToTotal(splitData: Seq[(String, Double)], flightPax: Int): Seq[(String, Double)] = {
    val total = splitData.map(_._2).sum
    splitData.map {
      case (split, pax) =>
        (split, Math.round((pax / total) * flightPax).toDouble)
    }
  }

}

