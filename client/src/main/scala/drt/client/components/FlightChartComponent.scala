package drt.client.components

import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.api.PassengerInfoSummary
import drt.shared.{ApiFlightWithSplits, PaxTypes}
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

      val sortedPaxTypes = p.passengerInfo.paxTypes.toList.sortBy(_._1.cleanName)
      val paxTypeData: ChartJsData = ChartJsData(sortedPaxTypes.map {
        case (pt, _) => PaxTypes.displayName(pt)
      }, sortedAges.map(_._2.toDouble), "Live API")


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
            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart",
              ChartJSComponent.HorizontalBar(
                ChartJsProps(
                  data = paxTypeData,
                  300,
                  300,
                  options = ChartJsOptions("Passenger Types")
                ))),
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
    .build

  def apply(props: Props): VdomElement = component(props)
}
