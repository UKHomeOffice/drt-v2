package drt.client.components

import drt.client.components.FlightComponents.DataQuality
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

  case class ForecastAccuracyDataQuality(daysOut: Int, errorPct: Option[Int]) extends DataQuality {
    private val label = s"$daysOut day${if (daysOut > 1) "s" else ""}"

    override val text: String = errorPct match {
      case Some(error) =>
        val sign = if (error >= 0) "+" else ""
        s"$label: $sign$error%"
      case None =>
        s"$label: N/A"
    }

    override val `type`: String = "info"
  }

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ApiStatus")
    .render_P { props =>
      val accuracyProxy = SPACircuit.connect(m => m.passengerForecastAccuracy)
      accuracyProxy { accuracyPot =>
        <.div(
          accuracyPot().renderReady { accuracy =>
            <.div(^.className := "status-bar-item",
              "Pax Forecast Accuracy",
              Tippy.info(<.div(
                ^.className := "tooltip-content",
                "The percentage displayed here shows how close to the actual number of passengers the forecast was for a number of days ahead.",
                <.ul(
                  <.li("+10% means the forecast was 10% above the actual"),
                  <.li("-10% means the forecast was 10% below the actual")
                )
              )),
              accuracy.pax.getOrElse(props.terminal, SortedMap[Int, Option[Double]]()).map {
                case (daysAhead, accuracyPct) =>
                  DataQualityIndicator(ForecastAccuracyDataQuality(daysAhead, accuracyPct.map(p => p.round.toInt)), props.terminal, s"$daysAhead-day-forecast", icon = true)
              }.toTagMod
            )
          })
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
