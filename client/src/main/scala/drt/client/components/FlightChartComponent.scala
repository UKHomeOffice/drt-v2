package drt.client.components

import drt.client.actions.Actions.GetPassengerInfoSummary
import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.ArrivalKey
import io.kinoplan.scalajs.react.material.ui.core.MuiCircularProgress
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.PaxTypes

import scala.scalajs.js

object FlightChartComponent {
  case class Props(flightWithSplits: ApiFlightWithSplits)

  case class State(showAllNationalities: Boolean)

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .initialState(State(false))
    .renderPS((scope, props, state) => {
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
                      val nationalitiesCount = if (state.showAllNationalities) info.nationalities.size else 10

                      val sortedNats = summariseNationalities(info.nationalities, nationalitiesCount)
                        .toList
                        .sortBy {
                          case (_, pax) => pax
                        }

                      val nationalityData = ChartJsData(
                        labels = sortedNats.map(_._1.code),
                        data = sortedNats.map(_._2.toDouble),
                        dataSetLabel = "Live API",
                        `type` = "bar")

                      val sortedAges = info.ageRanges.toList.sortBy(_._1.title)
                      val ageData = ChartJsData(
                        labels = sortedAges.map(_._1.title),
                        data = sortedAges.map(_._2.toDouble),
                        dataSetLabel = "Live API",
                        `type` = "bar")

                      val sortedPaxTypes = info.paxTypes.toList.sortBy(_._1.cleanName)

                      val toggleShowAllNationalities = (e: ReactEventFromInput) => {
                        val newValue: Boolean = e.target.checked
                        scope.modState(_.copy(showAllNationalities = newValue))
                      }

                      val chartHeight = 300
                      val widthFactor = if (info.nationalities.size > 10 && state.showAllNationalities) 5 else 0
                      val chartWidth: Int = if (dom.window.innerWidth > 800)
                        300 + widthFactor * info.nationalities.size
                      else
                        200 + widthFactor * info.nationalities.size

                      val paxTypeData: ChartJsData = ChartJsData(
                        labels = sortedPaxTypes.map {
                          case (pt, _) => PaxTypes.displayNameShort(pt)
                        },
                        data = sortedPaxTypes.map(_._2.toDouble),
                        dataSetLabel = "Live API",
                        `type` = "bar")

                      <.div(^.cls := "container arrivals__table__flight__chart-box",
                        <.div(^.cls := "row", ^.width := (chartWidth * 3).toString + "px",
                          if (sortedNats.toMap.values.sum > 0) {
                            val maxY = sortedNats.toMap.values.max + 5
                            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart nationality-chart",
                              chart("Nationality breakdown", nationalityData, maxY, chartWidth, chartHeight))
                          } else EmptyVdom,
                          if (sortedPaxTypes.toMap.values.sum > 0) {
                            val maxY = sortedPaxTypes.toMap.values.max + 5
                            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart passenger-type-chart",
                              chart("Passenger types", paxTypeData, maxY, chartWidth, chartHeight))
                          } else EmptyVdom,
                          if (sortedAges.toMap.values.sum > 0) {
                            val maxY = sortedAges.toMap.values.max + 5
                            <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart age-breakdown-chart",
                              chart("Age breakdown", ageData, maxY, chartWidth, chartHeight))
                          } else EmptyVdom
                        ),
                        if (info.nationalities.size > 10)
                          <.div(^.cls := s"arrivals__table__flight__chart__show__nationalities",
                            <.input.checkbox(^.className := "arrivals__table__flight__chart__show__nationalities_checkbox", ^.checked := state.showAllNationalities,
                              ^.onChange ==> toggleShowAllNationalities, ^.id := "toggle-showAllNationalities"),
                            <.label(^.className := "arrivals__table__flight__chart__show__nationalities_label", ^.`for` := "toggle-showAllNationalities", s"Show all ${info.nationalities.size} Nationalities")
                          )
                        else
                          EmptyVdom,
                      )
                    case None => <.div(MuiCircularProgress()(), ^.height := "282px", ^.display := "flex", ^.alignItems := "center", ^.justifyContent := "center")
                  }
                }
              )
            }
        ))
    }).build

  private def chart(title: String, data: ChartJsData, maxY: Int, width: Int, height: Int): UnmountedWithRawType[ChartJSComponent.Props, Null, RawMounted[ChartJSComponent.Props, Null]] =
    ChartJSComponent(
      ChartJsProps(
        data = data,
        width = Option(width),
        height = Option(height),
        options = ChartJsOptions(title).copy(
          scales = js.Dictionary[js.Any](
            "y" -> js.Dictionary(
              "suggestedMax" -> maxY,
            ),
          ))
      )
    )

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
