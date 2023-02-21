package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.Alert
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.html.Div


object AlertsComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Alerts")
    .render_P { _ =>
      val modelRCP = SPACircuit.connect(_.alerts)

      modelRCP { modelMP =>
        <.div(^.id := "alerts", renderAlerts(modelMP().getOrElse(List())))
      }
    }
    .build

  def nonProdEnvFromUrl(url: String): Option[String] = url match {
    case prodUrl if prodUrl contains ".drt.homeoffice" => None
    case preprodUrl if preprodUrl contains ".drt-preprod.homeoffice" => Option("PREPROD")
    case stagingUrl if stagingUrl contains ".drt-staging.homeoffice" => Option("STAGING")
    case _ => Option("TEST")
  }

  def nonProdAlert: Option[Alert] =
    nonProdEnvFromUrl(dom.document.location.href)
      .map(msg => Alert(msg, "this is not production", "warning", 0L, 0L))

  def renderAlerts(alerts: List[Alert]): VdomTagOf[Div] =
    <.div(^.className := "has-alerts grow",
      (nonProdAlert.toList ++ alerts).map(alert => {
        val message = if (alert.title.nonEmpty) s"${alert.title} - ${alert.message}" else alert.message
        <.span(
          ^.key := alert.createdAt,
          ^.`class` := s"alert alert-class-${alert.alertClass} the-alert",
          ^.role := "alert",
          message
        )
      }).toVdomArray
    )

  def apply(): VdomElement = component(Props())
}
