package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.SortedMap


object PassengerForecastAccuracyComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ApiStatus")
    .render_P { props =>
      val accuracyProxy = SPACircuit.connect(m => m.passengerForecastAccuracy)
      accuracyProxy { accuracyPot =>
        <.div(
          accuracyPot().renderReady { accuracy =>
            <.div(^.className := "status-bar-item",
              "Terminal Pax Forecast Accuracy",
              Tippy.info(<.div(
                "The percentage displayed here shows how close to the actual number of passengers the forecast was for the number of days ahead.",
                <.br(),
                "Numbers above 100% mean the forecast was too high, below means too low.",
                <.br(),
                <.br(),
                "eg",
                <.ul(
                  <.li("110% means the forecast was 10% over the actual"),
                  <.li("90% means 10% below the actual")
                )
              )),
              accuracy.pax.getOrElse(props.terminal, SortedMap[Int, Double]()).map {
                case (daysAhead, accuracyPct) =>
                  <.div(^.className := "status-bar-item-value neutral", s"$daysAhead day${if (daysAhead != 1) "s" else ""}: ${accuracyPct.toInt}%")
              }.toTagMod
            )
          })
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
