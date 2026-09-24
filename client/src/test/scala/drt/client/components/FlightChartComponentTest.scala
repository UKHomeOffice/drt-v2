package drt.client.components

import drt.client.components.FlightChartComponent._
import drt.client.components.charts.DataFormat.jsonString
import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.models.{FlightManifestSummary, ManifestKey, PaxAgeRange}
import uk.gov.homeoffice.drt.ports.{PaxType, PaxTypes, PortCode}
import utest._

import scala.collection.SortedMap
import scala.scalajs.js
import scala.scalajs.js.JSON

object FlightChartComponentTest extends TestSuite {
  private val eligibilityChangeMillis = SDate("2026-07-08T09:00Z").millisSinceEpoch

  private def summary(
    scheduled: Long = eligibilityChangeMillis - 1,
    ageRanges: SortedMap[PaxAgeRange, Int] = SortedMap.empty[PaxAgeRange, Int],
    nationalities: Map[Nationality, Int] = Map.empty,
    paxTypes: Map[PaxType, Int] = Map.empty,
  ): FlightManifestSummary =
    FlightManifestSummary(
      ManifestKey(PortCode("JFK"), VoyageNumber(1), scheduled),
      ageRanges,
      nationalities,
      paxTypes,
    )

  def tests: Tests = Tests {
    test("nationality series sorts by passenger count and converts values to doubles") {
      val result = nationalitySeries(summary(
        nationalities = Map(
          Nationality("USA") -> 5,
          Nationality("GBR") -> 1,
          Nationality("FRA") -> 3,
        ),
      ))

      assert(result.labels == List("GBR", "FRA", "USA"))
      assert(result.values == List(1, 3, 5))
      assert(result.data == List(1.0, 3.0, 5.0))
      assert(result.sum == 9)
      assert(result.maxY.contains(10))
    }

    test("age series preserves its source iteration order") {
      val ageRanges = SortedMap(
        PaxAgeRange.parse("0 to 7") -> 2,
        PaxAgeRange.parse("8 to 17") -> 4,
        PaxAgeRange.parse("18 to 24") -> 1,
      )

      val result = ageSeries(summary(ageRanges = ageRanges))

      assert(result.labels == ageRanges.toList.map(_._1.title))
      assert(result.values == ageRanges.toList.map(_._2))
      assert(result.data == List(2.0, 4.0, 1.0))
      assert(result.sum == 7)
      assert(result.maxY.contains(9))
    }

    test("pax-type series sorts by clean name and uses short labels") {
      val paxTypes: Map[PaxType, Int] = Map(
        PaxTypes.VisaNational -> 2,
        PaxTypes.GBRNational -> 5,
        PaxTypes.Transit -> 1,
      )
      val result = paxTypeSeries(summary(paxTypes = paxTypes))

      assert(result.labels == List("GBR", "Transit", "VN"))
      assert(result.values == List(5, 1, 2))
      assert(result.data == List(5.0, 1.0, 2.0))
      assert(result.sum == 8)
      assert(result.maxY.contains(10))
    }

    test("pax-type child labels are pre-change strictly before the eligibility instant") {
      val result = paxTypeSeries(summary(
        scheduled = eligibilityChangeMillis - 1,
        paxTypes = Map(PaxTypes.GBRNationalBelowEgateAge -> 1),
      ))

      assert(result.labels == List("GBR U10"))
    }

    test("pax-type child labels are post-change at and after the eligibility instant") {
      val atBoundary = paxTypeSeries(summary(
        scheduled = eligibilityChangeMillis,
        paxTypes = Map(PaxTypes.GBRNationalBelowEgateAge -> 1),
      ))
      val afterBoundary = paxTypeSeries(summary(
        scheduled = eligibilityChangeMillis + 1,
        paxTypes = Map(PaxTypes.GBRNationalBelowEgateAge -> 1),
      ))

      assert(atBoundary.labels == List("GBR U8"))
      assert(afterBoundary.labels == List("GBR U8"))
    }

    test("zero-only and empty series have no renderable maximum") {
      val zeroOnly = nationalitySeries(summary(
        nationalities = Map(Nationality("GBR") -> 0, Nationality("FRA") -> 0),
      ))
      val empty = ageSeries(summary())

      assert(zeroOnly.values == List(0, 0))
      assert(zeroOnly.data == List(0.0, 0.0))
      assert(zeroOnly.sum == 0)
      assert(zeroOnly.maxY.isEmpty)
      assert(empty.labels.isEmpty)
      assert(empty.values.isEmpty)
      assert(empty.data.isEmpty)
      assert(empty.sum == 0)
      assert(empty.maxY.isEmpty)
    }

    test("chart definitions retain render order, dataset settings, and per-chart options") {
      val result = chartDefinitions(summary(
        nationalities = Map(Nationality("GBR") -> 2),
        paxTypes = Map(PaxTypes.GBRNational -> 3),
        ageRanges = SortedMap(PaxAgeRange.parse("0 to 7") -> 4),
      ))

      assert(result.map(_.key) == List("pax-chart", "age-chart", "nat-chart"))
      assert(result.map(_.title) == List("Passenger types", "Age breakdown", "Nationality breakdown"))
      assert(result.map(_.chartClassName) == List(
        "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--pax",
        "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--age",
        "arrivals__table__flight__chart-box__chart arrivals__table__flight__chart-box__chart--nat",
      ))
      assert(result.map(_.allowAutoSkipX) == List(true, true, false))
      assert(result.map(_.isNationalityChart) == List(false, false, true))
      assert(result.forall(_.dataSetLabel == "Live API"))
      assert(result.forall(_.chartType == "bar"))
    }

    test("chart definitions retain zero-sum omission inputs") {
      val result = chartDefinitions(summary(
        nationalities = Map(Nationality("GBR") -> 0),
        paxTypes = Map(PaxTypes.GBRNational -> 0),
        ageRanges = SortedMap(PaxAgeRange.parse("0 to 7") -> 0),
      ))

      assert(result.map(_.series.sum) == List(0, 0, 0))
      assert(result.forall(_.series.maxY.isEmpty))
    }

    test("presentation layout retains the single-row threshold, dimensions, and classes") {
      val layout = FlightChartPresentation.layout(10, paxTypeLabelsTotal = 4, ageLabelsTotal = 6)

      assert(layout.chartHeightPx == 350)
      assert(layout.natChartWidth == "100%")
      assert(layout.paxChartWidth == "240px")
      assert(layout.ageChartWidth == "310px")
      assert(layout.stackedContentWidth == "310px")
      assert(!layout.shouldScaleNationalityToFit)
      assert(layout.wrapperClassName == "arrivals__table__flight__chart-wrapper arrivals__table__flight__chart-wrapper--single-row")
      assert(layout.chartBoxClassName == "arrivals__table__flight__chart-box arrivals__table__flight__chart-box--single-row")
      assert(layout.natScrollerClassName == "arrivals__table__flight__chart-nat-scroller")
    }

    test("presentation keeps 11-20 nationality charts compact without downscaling them") {
      val layout = FlightChartPresentation.layout(11, paxTypeLabelsTotal = 4, ageLabelsTotal = 6)

      assert(FlightChartPresentation.ChartChromeHeightPx == 120)
      assert(FlightChartPresentation.TwoRowDataAreaRatio == 0.6)
      assert(layout.chartHeightPx == 258)
      assert(layout.natChartNaturalWidth == "330px")
      assert(layout.natChartWidth == "580px")
      assert(layout.paxChartWidth == "240px")
      assert(layout.ageChartWidth == "310px")
      assert(layout.compactExtraColumnWidth == "0px")
      assert(layout.twoRowContentWidth == "580px")
      assert(layout.stackedContentWidth == "330px")
      assert(!layout.shouldScaleNationalityToFit)
      assert(layout.wrapperClassName == "arrivals__table__flight__chart-wrapper arrivals__table__flight__chart-wrapper--two-rows arrivals__table__flight__chart-wrapper--two-rows--compact")
      assert(layout.chartBoxClassName == "arrivals__table__flight__chart-box arrivals__table__flight__chart-box--two-rows arrivals__table__flight__chart-box--two-rows--compact")
      assert(layout.natScrollerClassName == "arrivals__table__flight__chart-nat-scroller arrivals__table__flight__chart-nat-scroller--enabled")
    }

    test("presentation scales nationality charts above twenty entries without changing the two-row height") {
      val layout = FlightChartPresentation.layout(21, paxTypeLabelsTotal = 4, ageLabelsTotal = 6)

      assert(layout.chartHeightPx == 258)
      assert(layout.natChartWidth == "630px")
      assert(layout.natChartNaturalWidth == "630px")
      assert(layout.paxChartWidth == "240px")
      assert(layout.ageChartWidth == "310px")
      assert(layout.twoRowContentWidth == "630px")
      assert(layout.stackedContentWidth == "630px")
      assert(layout.shouldScaleNationalityToFit)
      assert(layout.wrapperClassName == "arrivals__table__flight__chart-wrapper arrivals__table__flight__chart-wrapper--two-rows arrivals__table__flight__chart-wrapper--two-rows--expanded")
      assert(layout.chartBoxClassName == "arrivals__table__flight__chart-box arrivals__table__flight__chart-box--two-rows arrivals__table__flight__chart-box--two-rows--expanded")
      assert(layout.natScrollerClassName == "arrivals__table__flight__chart-nat-scroller arrivals__table__flight__chart-nat-scroller--enabled")
    }

    test("presentation caps top charts while allowing low-category charts to use less space") {
      val layout = FlightChartPresentation.layout(11, paxTypeLabelsTotal = 1, ageLabelsTotal = 10)

      assert(layout.paxChartWidth == "180px")
      assert(layout.ageChartWidth == "320px")
    }

    test("presentation stacked width ignores omitted top charts") {
      val layout = FlightChartPresentation.layout(11, paxTypeLabelsTotal = 0, ageLabelsTotal = 0)

      assert(layout.stackedContentWidth == "330px")
    }

    test("presentation reserves equal x-axis space for aligned chart baselines") {
      val options = FlightChartPresentation.chartOptions("Passenger types", 5, allowAutoSkipX = true).toJs.asInstanceOf[js.Dynamic]
      val axis = js.Dynamic.literal()
      val afterFit = options.selectDynamic("scales").selectDynamic("x").selectDynamic("afterFit")
        .asInstanceOf[js.Function1[js.Dynamic, Unit]]

      afterFit(axis)

      assert(axis.selectDynamic("height").asInstanceOf[Int] == FlightChartPresentation.XAxisHeightPx)
    }

    test("presentation options retain the complete nationality chart configuration") {
      val result = FlightChartPresentation.chartOptions("Nationality breakdown", 15, allowAutoSkipX = false).toJs
      val expected = JSON.parse(
        """{
          |  "scales": {
          |    "x": { "ticks": { "autoSkip": false, "font": { "size": 14 } } },
          |    "y": { "suggestedMax": 15, "ticks": { "font": { "size": 14 } } }
          |  },
          |  "plugins": {
          |    "title": { "display": true, "text": "Nationality breakdown", "align": "start", "font": { "size": 14 } },
          |    "legend": { "display": true, "align": "end", "labels": { "font": { "size": 14 } } },
          |    "tooltip": { "titleFont": { "size": 14 }, "bodyFont": { "size": 14 }, "footerFont": { "size": 14 } }
          |  },
          |  "responsive": true,
          |  "maintainAspectRatio": false,
          |  "layout": { "padding": 8 }
          |}""".stripMargin
      )

      assert(jsonString(result) == jsonString(expected))
    }

    test("presentation options retain auto-skip for passenger-type and age charts") {
      val result = FlightChartPresentation.chartOptions("Passenger types", 5, allowAutoSkipX = true).toJs
      val expected = JSON.parse(
        """{
          |  "scales": {
          |    "x": { "ticks": { "autoSkip": true, "font": { "size": 14 } } },
          |    "y": { "suggestedMax": 5, "ticks": { "font": { "size": 14 } } }
          |  },
          |  "plugins": {
          |    "title": { "display": true, "text": "Passenger types", "align": "start", "font": { "size": 14 } },
          |    "legend": { "display": true, "align": "end", "labels": { "font": { "size": 14 } } },
          |    "tooltip": { "titleFont": { "size": 14 }, "bodyFont": { "size": 14 }, "footerFont": { "size": 14 } }
          |  },
          |  "responsive": true,
          |  "maintainAspectRatio": false,
          |  "layout": { "padding": 8 }
          |}""".stripMargin
      )

      assert(jsonString(result) == jsonString(expected))
    }
  }
}
