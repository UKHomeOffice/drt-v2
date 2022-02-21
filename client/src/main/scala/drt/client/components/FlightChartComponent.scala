package drt.client.components

import drt.client.actions.Actions.GetPassengerInfoSummary
import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.ArrivalKey
import io.kinoplan.scalajs.react.material.ui.core.MuiCircularProgress
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.PaxTypes

object FlightChartComponent {
  case class Props(flightWithSplits: ApiFlightWithSplits)

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component = ScalaComponent.builder[Props]("FlightChart")
    .render_P { props =>
      val proxy = SPACircuit.connect(_.passengerInfoSummariesByArrival)

      <.div(^.id := "charts-box",
        Tippy.interactiveInfo(
          triggerCallback = Option((_: ReactEventFromInput) => {
            Callback(SPACircuit.dispatch(GetPassengerInfoSummary(ArrivalKey(props.flightWithSplits.apiFlight))))
          }),
          content =
            proxy { rcp =>
              val infosPot = rcp()
              <.div(^.cls := "container arrivals__table__flight__chart-box",
                infosPot.render { infos =>
                  infos.get(ArrivalKey(props.flightWithSplits.apiFlight)) match {
                    case Some(info) =>
                      val sortedNats = summariseNationalities(info.nationalities, 10)
                        .toList
                        .sortBy {
                          case (_, pax) => pax
                        }

                      val nationalityData = ChartJsData(sortedNats.map(_._1.code), sortedNats.map(_._2.toDouble), "Live API")

                      val sortedAges = info.ageRanges.toList.sortBy(_._1.title)
                      val ageData: ChartJsData = ChartJsData(sortedAges.map(_._1.title), sortedAges.map(_._2.toDouble), "Live API")

                      val sortedPaxTypes = info.paxTypes.toList.sortBy(_._1.cleanName)

                      val paxTypeData: ChartJsData = ChartJsData(sortedPaxTypes.map {
                        case (pt, _) => PaxTypes.displayNameShort(pt)
                      }, sortedPaxTypes.map(_._2.toDouble), "Live API")
                      <.div(^.cls := "row",
                        if (sortedNats.toMap.values.sum > 0)
                          <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart nationality-chart",
                            ChartJSComponent.Bar(
                              ChartJsProps(
                                data = nationalityData,
                                300,
                                300,
                                options = ChartJsOptions.withSuggestedMax("Nationality breakdown", sortedNats.toMap.values.max + 5)
                              )
                            ))
                        else
                          EmptyVdom,
                        if (sortedPaxTypes.toMap.values.sum > 0 && sortedAges.toMap.values.sum > 0)
                          <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart passenger-type-chart",
                            ChartJSComponent.Bar(
                              ChartJsProps(
                                data = paxTypeData,
                                300,
                                300,
                                options = ChartJsOptions.withSuggestedMax("Passenger types", sortedPaxTypes.toMap.values.max + 5)
                              )))
                        else
                          EmptyVdom,
                        if (sortedAges.toMap.values.sum > 0)
                          <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart age-breakdown-chart",
                            ChartJSComponent.Bar(
                              ChartJsProps(
                                data = ageData,
                                300,
                                300,
                                options = ChartJsOptions.withSuggestedMax("Age breakdown", sortedAges.toMap.values.max + 5)
                              ))
                          )
                        else
                          EmptyVdom
                      )
                    case None => <.div(MuiCircularProgress()(), ^.height := "282px", ^.display := "flex", ^.alignItems := "center", ^.justifyContent := "center")
                  }
                }
              )
            }
        ))
    }
    .build

  def summariseNationalities(nats: Map[Nationality, Int], numberToShow: Int): Map[Nationality, Int] =
    nats
      .toList
      .sortBy {
        case (_, total) => total
      }
      .reverse
      .splitAt(numberToShow) match {
      case (relevant, other) if other.nonEmpty =>
        relevant.toMap + (Nationality("Other") -> other.map(_._2).sum)
      case (all, _) => all.toMap
    }

  def apply(props: Props): VdomElement = component(props)
}
