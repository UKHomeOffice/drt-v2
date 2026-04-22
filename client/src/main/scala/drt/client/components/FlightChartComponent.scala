package drt.client.components

import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import io.kinoplan.scalajs.react.material.ui.core.MuiAlert
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.models.FlightManifestSummary
import uk.gov.homeoffice.drt.ports.PaxTypes

import scala.scalajs.js

object FlightChartComponent {
  case class Props(manifestSummary: FlightManifestSummary, maybeMissingPaxCount: Option[Int])

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .render_P { props =>

      val maybeWarning = props.maybeMissingPaxCount.collect {
        case missingPaxCount if missingPaxCount > 0 =>
          val apiPaxCount = props.manifestSummary.passengerCount
          val totalPax = apiPaxCount + missingPaxCount
          f"DRT has received $apiPaxCount out of $totalPax passenger records for this flight."
      }

      val nationalitiesTotal = props.manifestSummary.nationalities.size
      val shouldBreakIntoTwoRows = nationalitiesTotal > 10
      val chartHeightPx = if (shouldBreakIntoTwoRows) 210 else 350

      val wrapperClassName =
        "arrivals__table__flight__chart-wrapper" +
          (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-wrapper--two-rows" else " arrivals__table__flight__chart-wrapper--single-row")

      val chartBoxClassName =
        "arrivals__table__flight__chart-box" +
          (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-box--two-rows" else " arrivals__table__flight__chart-box--single-row")

      val natScrollerClassName =
        "arrivals__table__flight__chart-nat-scroller" +
          (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-nat-scroller--enabled" else "")

      val sortedNats = props.manifestSummary.nationalities
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

      val natsChartWidth = 30 * nationalitiesTotal

      val natChartWidthStyle: TagMod =
        if (shouldBreakIntoTwoRows) ^.width := s"${natsChartWidth}px" else ^.width := "100%"

      val isBeforeAgeEligibilityChangeDate: Long => Boolean = scheduled => scheduled < SDate("2023-07-25T00:00").millisSinceEpoch

      val paxTypeData: ChartJsData = ChartJsData(
        labels = sortedPaxTypes.map {
          case (pt, _) => PaxTypes.displayNameShort(pt, isBeforeAgeEligibilityChangeDate(props.manifestSummary.arrivalKey.scheduled))
        },
        data = sortedPaxTypes.map(_._2.toDouble),
        dataSetLabel = "Live API",
        `type` = "bar")

      <.div(^.className := "arrivals__table__flight__chart-box-wrapper",
        Tippy.interactiveInfo(
          gaEventLabel = "arrival-table-flight-chart-box",
          theme = "light-border flight-chart-tooltip",
          content =
            <.div(
              ^.cls := chartBoxClassName,
              maybeWarning.map(MuiAlert(variant = MuiAlert.Variant.standard, severity = "warning")(_)).getOrElse(<.div()),

              <.div(^.className := wrapperClassName,
                if (sortedNats.toMap.values.sum > 0) {
                  val maxY = sortedNats.toMap.values.max + 5
                  <.div(
                    ^.key := "nat-chart-scroller",
                    ^.className := natScrollerClassName,
                    <.div(
                      ^.key := "nat-chart",
                      ^.cls := "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--nat",
                      natChartWidthStyle,
                      ^.height := s"${chartHeightPx}px",
                      chart("Nationality breakdown", nationalityData, maxY))
                  )
                } else EmptyVdom,
                if (sortedPaxTypes.toMap.values.sum > 0) {
                  val maxY = sortedPaxTypes.toMap.values.max + 5
                  <.div(
                    ^.key := "pax-chart",
                    ^.cls := "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--pax",
                    ^.height := s"${chartHeightPx}px",
                    chart("Passenger types", paxTypeData, maxY, allowAutoSkipX = true))
                } else EmptyVdom,
                if (sortedAges.toMap.values.sum > 0) {
                  val maxY = sortedAges.toMap.values.max + 5
                  <.div(
                    ^.key := "age-chart",
                    ^.cls := "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--age",
                    ^.height := s"${chartHeightPx}px",
                    chart("Age breakdown", ageData, maxY, allowAutoSkipX = true))
                } else EmptyVdom
              ),
            )
        ))
    }.build

  private def chart(title: String,
                    data: ChartJsData,
                    maxY: Int,
                    allowAutoSkipX: Boolean = false): UnmountedWithRawType[ChartJSComponent.Props, Null, RawMounted[ChartJSComponent.Props, Null]] = {
    val font16 = js.Dictionary[js.Any]("size" -> 16)

    val plugins = js.Dictionary[js.Any](
      "title" -> js.Dictionary(
        "display" -> true,
        "text" -> title,
        "align" -> "start",
        "font" -> font16,
      ),
      "legend" -> js.Dictionary(
        "display" -> true,
        "align" -> "end",
        "labels" -> js.Dictionary(
          "font" -> font16,
        ),
      ),
      "tooltip" -> js.Dictionary(
        "titleFont" -> font16,
        "bodyFont" -> font16,
        "footerFont" -> font16,
      )
    )

    ChartJSComponent(
      ChartJsProps(
        data = data,
        width = None,
        height = None,
        options = ChartJsOptions(title).copy(
          plugins = plugins,
          responsive = true,
          maintainAspectRatio = false,
          scales = js.Dictionary[js.Any](
            "x" -> js.Dictionary(
              "ticks" -> js.Dictionary(
                "autoSkip" -> allowAutoSkipX,
                "font" -> font16,
              )
            ),
            "y" -> js.Dictionary(
              "suggestedMax" -> maxY,
              "ticks" -> js.Dictionary(
                "font" -> font16,
              ),
            ),
          ))
      )
    )
  }


  def apply(props: Props): VdomElement = component(props)
}
