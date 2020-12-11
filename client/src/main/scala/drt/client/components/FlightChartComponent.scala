package drt.client.components

import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.charts.ChartData
import drt.shared.ApiFlightWithSplits
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.api.PassengerInfoSummary
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

object FlightChartComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    flightWithSplits: ApiFlightWithSplits,
                    passengerInfo: PassengerInfoSummary
                  )

  val component = ScalaComponent.builder[Props]("FlightChart")
    .render_P(p => {
      val sortedNats = p.passengerInfo.nationalities.toList.sortBy(_._1.code)
      val nationalityData = ChartJsData(sortedNats.map(_._1.code), sortedNats.map(_._2.toDouble), "Live API")

      val sortedAges = p.passengerInfo.ageRanges.toList.sortBy(_._1.title)

      val ageData: ChartJsData = ChartJsData(sortedAges.map(_._1.title), sortedAges.map(_._2.toDouble), "Live API")

      val maybeLiveApiPaxTypes: Option[ChartJsData] = p.flightWithSplits.splits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages).map(splits =>

        ChartData.splitToPaxTypeData(splits.splits, "Live API")
      )

      TippyJSComponent(
        <.div(^.cls := "container arrivals__table__flight__chart-box",
          <.div(^.cls := "row",
            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart",
              ChartJSComponent.HorizontalBar(
                ChartJsProps(
                  data = nationalityData,
                  300,
                  300,
                  options = ChartJsOptions("Nationality Breakdown")
                )
              )),
            maybeLiveApiPaxTypes.map(liveAPIPaxTypes =>
              <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart",
                ChartJSComponent.HorizontalBar(
                  ChartJsProps(
                    data = liveAPIPaxTypes,
                    300,
                    300,
                    options = ChartJsOptions("Passenger Types")
                  )))
            ),
            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart",
              ChartJSComponent.HorizontalBar(
                ChartJsProps(
                  data = ageData,
                  300,
                  300,
                  options = ChartJsOptions("Age Breakdown")
                ))
            )
          )
        ).rawElement, interactive = true, <.span(Icon.infoCircle))
    })

    //      p.flightWithSplits.splits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages).map(
    //        (splits: Splits) => {

    //          val nationalityData: ChartJsData = ChartData.splitToNationalityChartData(splits.splits)
    //
    //          val liveAPIPaxTypes: ChartJsData = ChartData.splitToPaxTypeData(splits.splits, "Live API")
    //
    //          //              val paxTypeSplitComparison: Seq[ChartJsData] = flightWithSplits
    //          //                .splits
    //          //                .find(_.source == SplitSources.Historical)
    //          //                .map(s => ChartData.splitToPaxTypeData(s.splits, "Historic")).toSeq :+ liveAPIPaxTypes
    //          //
    //          //              val paxTypeData: Seq[ChartJsData] = paxTypeSplitComparison
    //
    //          val ageData: ChartJsData = ChartData.splitDataToAgeRanges(splits.splits)


    //    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
