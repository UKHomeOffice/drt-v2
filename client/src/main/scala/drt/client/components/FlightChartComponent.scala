package drt.client.components

import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.api.FlightManifestSummary
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.PaxTypes

import scala.scalajs.js

object FlightChartComponent {
  case class Props(manifestSummary: FlightManifestSummary)

  case class State(showAllNationalities: Boolean)

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .initialState(State(false))
    .renderPS((scope, props, state) => {
      val nationalitiesCount = if (state.showAllNationalities) props.manifestSummary.nationalities.size else 10

      val sortedNats = summariseNationalities(props.manifestSummary.nationalities, nationalitiesCount)
        .toList
        .sortBy {
          case (_, pax) => pax
        }

      val nationalityData = ChartJsData(
        labels = sortedNats.map(_._1.code),
        data = sortedNats.map(_._2.toDouble),
        dataSetLabel = "Live API",
        `type` = "bar")

      val sortedAges = props.manifestSummary.ageRanges.toList.sortBy(_._1.title)
      val ageData = ChartJsData(
        labels = sortedAges.map(_._1.title),
        data = sortedAges.map(_._2.toDouble),
        dataSetLabel = "Live API",
        `type` = "bar")

      val sortedPaxTypes = props.manifestSummary.paxTypes.toList.sortBy(_._1.cleanName)

      val toggleShowAllNationalities = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        scope.modState(_.copy(showAllNationalities = newValue))
      }

      val chartHeight = 350
      val widthFactor = if (props.manifestSummary.nationalities.size > 10 && state.showAllNationalities) 5 else 0
      val chartWidth: Int = if (dom.window.innerWidth > 800)
        300 + widthFactor * props.manifestSummary.nationalities.size
      else
        200 + widthFactor * props.manifestSummary.nationalities.size

      val paxTypeData: ChartJsData = ChartJsData(
        labels = sortedPaxTypes.map {
          case (pt, _) => PaxTypes.displayNameShort(pt)
        },
        data = sortedPaxTypes.map(_._2.toDouble),
        dataSetLabel = "Live API",
        `type` = "bar")

      <.div(^.className := "arrivals__table__flight__chart-box-wrapper",
        Tippy.interactiveInfo(
          content =
            <.div(^.cls := "arrivals__table__flight__chart-box",
              <.div(^.className := "arrivals__table__flight__chart-wrapper", ^.width := (chartWidth * 3).toString + "px",
                if (sortedNats.toMap.values.sum > 0) {
                  val maxY = sortedNats.toMap.values.max + 5
                  <.div(^.key := "nat-chart", ^.cls := "arrivals__table__flight__chart-box__chart",
                    chart("Nationality breakdown", nationalityData, maxY, chartWidth, chartHeight))
                } else EmptyVdom,
                if (sortedPaxTypes.toMap.values.sum > 0) {
                  val maxY = sortedPaxTypes.toMap.values.max + 5
                  <.div(^.key := "pax-chart", ^.cls := "arrivals__table__flight__chart-box__chart",
                    chart("Passenger types", paxTypeData, maxY, chartWidth, chartHeight))
                } else EmptyVdom,
                if (sortedAges.toMap.values.sum > 0) {
                  val maxY = sortedAges.toMap.values.max + 5
                  <.div(^.key := "age-chart", ^.cls := "arrivals__table__flight__chart-box__chart",
                    chart("Age breakdown", ageData, maxY, chartWidth, chartHeight))
                } else EmptyVdom
              ),
              if (props.manifestSummary.nationalities.size > 10)
                <.div(^.key := "toggle-all-nats", ^.cls := s"arrivals__table__flight__chart__show__nationalities",
                  <.input.checkbox(^.className := "arrivals__table__flight__chart__show__nationalities_checkbox", ^.checked := state.showAllNationalities,
                    ^.onChange ==> toggleShowAllNationalities, ^.id := "toggle-showAllNationalities"),
                  <.label(^.className := "arrivals__table__flight__chart__show__nationalities_label", ^.`for` := "toggle-showAllNationalities", s"Show all ${props.manifestSummary.nationalities.size} Nationalities")
                )
              else
                EmptyVdom,
            )
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
            "xAxes" -> js.Dictionary(
              "ticks" -> js.Dictionary(
                "autoSkip" -> false,
              )
            ),
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
