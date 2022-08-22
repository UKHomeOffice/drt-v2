package drt.client.components

import diode.UseValueEq
import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsOptions, ChartJsProps}
import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.raw.HTMLElement
import uk.gov.homeoffice.drt.Nationality

object NationalityFinderChartComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .render_P(p => {
      val sortedNats = p.nationalities
        .toList
        .sortBy {
          case (_, pax) => pax
        }

      val nationalityData = ChartJsData(sortedNats.map(_._1.code), sortedNats.map(_._2.toDouble), "Live API")

      Tippy.interactive(
        <.div(^.cls := "container arrivals__table__flight__chart-box-1",
          <.div(^.cls := "row",
            if (sortedNats.toMap.values.sum > 0)
              <.div(^.cls := "col-sm arrivals__table__flight__chart-box__chart nationality-chart",
                ChartJSComponent(
                  ChartJsProps(
                    data = nationalityData,
                    300,
                    300,
                    options = ChartJsOptions.withSuggestedMax("Nationality breakdown", sortedNats.toMap.values.max + 5)
                  )
                ))
            else
              EmptyVdom
          )
        ),
        p.trigger
      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)

  case class Props(nationalities: Map[Nationality, Int], trigger: VdomTagOf[HTMLElement]) extends UseValueEq

}
