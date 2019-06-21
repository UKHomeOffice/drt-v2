package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.Alert
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.ScalaComponent
import org.scalajs.dom.html.Span


object AlertsComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component = ScalaComponent.builder[Props]("Alerts")
    .render_P(_ => {

      val modelRCP = SPACircuit.connect(_.alerts)

      modelRCP { modelMP =>
        val alertsPot = modelMP()

        <.div(^.id := "alerts",
          alertsPot.render(renderAlert),
          alertsPot.renderEmpty(<.div(^.id := "empty-alert"))
        )
      }
    })
    .build

  def renderAlert(alerts: List[Alert]): VdomTagOf[Span] =
    <.span(^.className := "has-alerts",
      alerts.map(alert => {
        val message = if (alert.title.nonEmpty) s"${alert.title} - ${alert.message}" else alert.message
        <.span(^.key := alert.createdAt, ^.`class` := s"alert alert-class-${alert.alertClass} the-alert", ^.role := "alert",
          ReactMarkdown.component(ReactMarkdown.props(message))
        )
      }).toVdomArray
    )


  def apply(): VdomElement = component(Props())
}
