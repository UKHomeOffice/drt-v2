package drt.client.components

import drt.client.components.ChartJSComponent.{ ChartJsData, ChartJsOptions, ChartJsProps }
import drt.client.logger.{ Logger, LoggerFactory }
import drt.client.services.JSDateConversions.SDate
import io.kinoplan.scalajs.react.material.ui.core.MuiAlert
import japgolly.scalajs.react.component.Js.{ RawMounted, UnmountedWithRawType }
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ CtorType, ScalaComponent }
import uk.gov.homeoffice.drt.models.FlightManifestSummary
import uk.gov.homeoffice.drt.ports.PaxTypes

import scala.scalajs.js

object FlightChartComponent {
  case class Props(manifestSummary: FlightManifestSummary, maybeMissingPaxCount: Option[Int])

  private[components] final case class Series(labels: List[String], values: List[Int]) {
    def data: List[Double] = values.map(_.toDouble)
    def sum: Int = values.sum
    def maxY: Option[Int] = if (sum > 0) Some(values.max + FlightChartPresentation.MaxYPadding) else None
  }

  private[components] final case class ChartDefinition(
      key: String,
      title: String,
      chartClassName: String,
      series: Series,
      allowAutoSkipX: Boolean,
      isNationalityChart: Boolean = false,
      dataSetLabel: String = "Live API",
      chartType: String = "bar"
  ) {
    def data: ChartJsData = ChartJsData(
      labels = series.labels,
      data = series.data,
      dataSetLabel = dataSetLabel,
      `type` = chartType
    )
  }

  private[components] object FlightChartPresentation {
    val TwoRowNationalityThreshold: Int = 10
    val SingleRowChartHeightPx: Int = 350
    val ChartChromeHeightPx: Int = 120
    val TwoRowDataAreaRatio: Double = 0.6
    val TwoRowChartHeightPx: Int =
      (ChartChromeHeightPx + ((SingleRowChartHeightPx - ChartChromeHeightPx) * TwoRowDataAreaRatio)).round.toInt
    val NationalityWidthPerEntryPx: Int = 30
    val TopChartWidthPerEntryPx: Int = 35
    val TopChartSideAllowancePx: Int = 100
    val MinTopChartWidthPx: Int = 180
    val MaxTopChartWidthPx: Int = 320
    val ChartGapPx: Int = 15
    val ScaleNationalityToFitThreshold: Int = 20
    val MaxYPadding: Int = 5
    val XAxisHeightPx: Int = 80
    val ChartLayoutPaddingPx: Int = 8

    final case class Layout(
        chartHeightPx: Int,
        natChartWidth: String,
        natChartNaturalWidth: String,
        paxChartWidth: String,
        ageChartWidth: String,
        compactExtraColumnWidth: String,
        twoRowContentWidth: String,
        stackedContentWidth: String,
        shouldScaleNationalityToFit: Boolean,
        wrapperClassName: String,
        chartBoxClassName: String,
        natScrollerClassName: String
    )

    private def topChartWidth(labelCount: Int): Int =
      math.max(MinTopChartWidthPx, math.min(MaxTopChartWidthPx, TopChartSideAllowancePx + (TopChartWidthPerEntryPx * labelCount)))

    def layout(nationalitiesTotal: Int, paxTypeLabelsTotal: Int, ageLabelsTotal: Int): Layout = {
      val shouldBreakIntoTwoRows = nationalitiesTotal > TwoRowNationalityThreshold
      val shouldScaleNationalityToFit = nationalitiesTotal > ScaleNationalityToFitThreshold
      val paxChartWidthPx = topChartWidth(paxTypeLabelsTotal)
      val ageChartWidthPx = topChartWidth(ageLabelsTotal)
      val natChartNaturalWidthPx = NationalityWidthPerEntryPx * nationalitiesTotal
      val compactContentWidthPx = math.max(
        natChartNaturalWidthPx,
        paxChartWidthPx + ageChartWidthPx + (2 * ChartGapPx)
      )
      val compactExtraColumnWidthPx = math.max(
        0,
        compactContentWidthPx - paxChartWidthPx - ageChartWidthPx - (2 * ChartGapPx)
      )
      val stackedContentWidthPx = List(
        Option.when(paxTypeLabelsTotal > 0)(paxChartWidthPx),
        Option.when(ageLabelsTotal > 0)(ageChartWidthPx),
        Option.when(nationalitiesTotal > 0)(natChartNaturalWidthPx)
      ).flatten.maxOption.getOrElse(0)
      val twoRowVariantClassName =
        if (shouldScaleNationalityToFit) " arrivals__table__flight__chart-wrapper--two-rows--expanded"
        else " arrivals__table__flight__chart-wrapper--two-rows--compact"

      Layout(
        chartHeightPx = if (shouldBreakIntoTwoRows) TwoRowChartHeightPx else SingleRowChartHeightPx,
        natChartWidth =
          if (shouldScaleNationalityToFit) s"${natChartNaturalWidthPx}px"
          else if (shouldBreakIntoTwoRows) s"${compactContentWidthPx}px"
          else "100%",
        natChartNaturalWidth = s"${natChartNaturalWidthPx}px",
        paxChartWidth = s"${paxChartWidthPx}px",
        ageChartWidth = s"${ageChartWidthPx}px",
        compactExtraColumnWidth = s"${compactExtraColumnWidthPx}px",
        twoRowContentWidth = s"${compactContentWidthPx}px",
        stackedContentWidth = s"${stackedContentWidthPx}px",
        shouldScaleNationalityToFit = shouldScaleNationalityToFit,
        wrapperClassName =
          "arrivals__table__flight__chart-wrapper" +
            (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-wrapper--two-rows" + twoRowVariantClassName
             else " arrivals__table__flight__chart-wrapper--single-row"),
        chartBoxClassName =
          "arrivals__table__flight__chart-box" +
            (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-box--two-rows" + twoRowVariantClassName.replace("chart-wrapper", "chart-box")
             else " arrivals__table__flight__chart-box--single-row"),
        natScrollerClassName =
          "arrivals__table__flight__chart-nat-scroller" +
            (if (shouldBreakIntoTwoRows) " arrivals__table__flight__chart-nat-scroller--enabled" else "")
      )
    }

    def chartOptions(title: String, maxY: Int, allowAutoSkipX: Boolean): ChartJsOptions = {
      val font14 = js.Dictionary[js.Any]("size" -> 14)
      val reserveXAxisSpace: js.Function1[js.Dynamic, Unit] =
        ((axis: js.Dynamic) => axis.updateDynamic("height")(XAxisHeightPx)): js.Function1[js.Dynamic, Unit]

      val plugins = js.Dictionary[js.Any](
        "title" -> js.Dictionary(
          "display" -> true,
          "text" -> title,
          "align" -> "start",
          "font" -> font14
        ),
        "legend" -> js.Dictionary(
          "display" -> true,
          "align" -> "end",
          "labels" -> js.Dictionary(
            "font" -> font14
          )
        ),
        "tooltip" -> js.Dictionary(
          "titleFont" -> font14,
          "bodyFont" -> font14,
          "footerFont" -> font14
        )
      )

      ChartJsOptions(title).copy(
        plugins = plugins,
        responsive = true,
        maintainAspectRatio = false,
        layout = js.Dictionary[js.Any]("padding" -> ChartLayoutPaddingPx),
        scales = js.Dictionary[js.Any](
          "x" -> js.Dictionary(
            "ticks" -> js.Dictionary(
              "autoSkip" -> allowAutoSkipX,
              "font" -> font14
            ),
            "afterFit" -> reserveXAxisSpace
          ),
          "y" -> js.Dictionary(
            "suggestedMax" -> maxY,
            "ticks" -> js.Dictionary(
              "font" -> font14
            )
          )
        )
      )
    }
  }

  private[components] def nationalitySeries(summary: FlightManifestSummary): Series = {
    val sortedNats = summary.nationalities.toList.sortBy {
      case (_, pax) => pax
    }

    Series(sortedNats.map(_._1.code), sortedNats.map(_._2))
  }

  private[components] def ageSeries(summary: FlightManifestSummary): Series = {
    val ageRanges = summary.ageRanges.toList
    Series(ageRanges.map(_._1.title), ageRanges.map(_._2))
  }

  private[components] def paxTypeSeries(summary: FlightManifestSummary): Series = {
    val sortedPaxTypes = summary.paxTypes.toList.sortBy(_._1.cleanName)
    val isBeforeAgeEligibilityChangeDate: Long => Boolean =
      scheduled => scheduled < SDate("2026-07-08T09:00Z").millisSinceEpoch

    Series(
      sortedPaxTypes.map {
        case (paxType, _) =>
          PaxTypes.displayNameShort(paxType, isBeforeAgeEligibilityChangeDate(summary.arrivalKey.scheduled))
      },
      sortedPaxTypes.map(_._2)
    )
  }

  private[components] def chartDefinitions(summary: FlightManifestSummary): List[ChartDefinition] = List(
    ChartDefinition(
      key = "pax-chart",
      title = "Passenger types",
      chartClassName = "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--pax",
      series = paxTypeSeries(summary),
      allowAutoSkipX = true
    ),
    ChartDefinition(
      key = "age-chart",
      title = "Age breakdown",
      chartClassName = "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--age",
      series = ageSeries(summary),
      allowAutoSkipX = true
    ),
    ChartDefinition(
      key = "nat-chart",
      title = "Nationality breakdown",
      chartClassName = "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--nat",
      series = nationalitySeries(summary),
      allowAutoSkipX = false,
      isNationalityChart = true
    )
  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .render_P { props =>
      val maybeWarning = props.maybeMissingPaxCount.collect {
        case missingPaxCount if missingPaxCount > 0 =>
          val apiPaxCount = props.manifestSummary.passengerCount
          val totalPax = apiPaxCount + missingPaxCount
          f"DRT has received $apiPaxCount out of $totalPax passenger records for this flight."
      }

      val charts = chartDefinitions(props.manifestSummary)
      val renderedLabelCount = (key: String) => charts.find(_.key == key).filter(_.series.sum > 0).fold(0)(_.series.labels.size)
      val layout = FlightChartPresentation.layout(
        props.manifestSummary.nationalities.size,
        renderedLabelCount("pax-chart"),
        renderedLabelCount("age-chart")
      )

      <.div(
        ^.className := "arrivals__table__flight__chart-box-wrapper",
        Tippy.interactiveInfo(
          gaEventLabel = "arrival-table-flight-chart-box",
          theme = "light-border flight-chart-tooltip",
          content =
            <.div(
              ^.cls := layout.chartBoxClassName,
              ^.style := js.Dictionary(
                "--flight-chart-default-height" -> s"${layout.chartHeightPx}px",
                "--flight-chart-full-height" -> s"${FlightChartPresentation.SingleRowChartHeightPx}px",
                "--flight-chart-pax-width" -> layout.paxChartWidth,
                "--flight-chart-age-width" -> layout.ageChartWidth,
                "--flight-chart-compact-extra-width" -> layout.compactExtraColumnWidth,
                "--flight-chart-two-row-content-width" -> layout.twoRowContentWidth,
                "--flight-chart-stacked-content-width" -> layout.stackedContentWidth
              ),
              maybeWarning.map(MuiAlert(
                variant = MuiAlert.Variant.standard,
                severity = "warning"
              )(_)).getOrElse(<.div()),

              <.div(
                ^.className := layout.wrapperClassName,
                charts.map(renderChart(_, layout)).toTagMod
              )

            )
        )
      )
    }.build

  private def renderChart(chartDefinition: ChartDefinition, layout: FlightChartPresentation.Layout): VdomNode = {
    if (chartDefinition.series.sum <= 0) EmptyVdom
    else {
      val maxY = chartDefinition.series.maxY.fold(0)(identity)
      val chartNode = <.div(
        ^.key := chartDefinition.key,
        ^.cls := chartDefinition.chartClassName +
          (if (chartDefinition.isNationalityChart && layout.shouldScaleNationalityToFit)
             " arrivals__table__flight__chart-box__chart--nat-scaled"
           else ""),
        if (chartDefinition.isNationalityChart)
          ^.style := js.Dictionary(
            "width" -> layout.natChartWidth,
            "--flight-chart-nationality-natural-width" -> layout.natChartNaturalWidth
          )
        else EmptyVdom,
        ^.height := "var(--flight-chart-height)",
        chart(chartDefinition.title, chartDefinition.data, maxY, chartDefinition.allowAutoSkipX)
      )

      if (chartDefinition.isNationalityChart)
        <.div(
          ^.key := "nat-chart-scroller",
          ^.className := layout.natScrollerClassName,
          chartNode
        )
      else chartNode
    }
  }

  private def chart(
      title: String,
      data: ChartJsData,
      maxY: Int,
      allowAutoSkipX: Boolean
  ): UnmountedWithRawType[ChartJSComponent.Props, Null, RawMounted[ChartJSComponent.Props, Null]] = {
    ChartJSComponent(
      ChartJsProps(
        data = data,
        width = None,
        height = None,
        options = FlightChartPresentation.chartOptions(title, maxY, allowAutoSkipX)
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
