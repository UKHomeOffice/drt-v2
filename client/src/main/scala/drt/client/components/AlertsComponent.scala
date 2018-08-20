package drt.client.components

import drt.client.actions.Actions.CloseAlerts
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.Alert
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

object AlertsComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component = ScalaComponent.builder[Props]("Alerts")
    .render_P(_ => {

      def closeAlerts = (_: ReactEventFromInput) =>
        Callback(SPACircuit.dispatch(CloseAlerts))

      val modelRCP = SPACircuit.connect(m => m.alerts)

      modelRCP { modelMP =>
        val alertsPot = modelMP()

        <.div(^.id:= "alerts",
          alertsPot.render((alerts: Seq[Alert]) => {
          <.span(^.id:= "has-alerts",
            <.span(^.id :="close-alert", ^.className := "close", ^.onClick ==> closeAlerts, ^.title :="Close", "X"),
            alerts.map(alert => {
              <.span(^.key := alert.createdAt,
              <.h3(alert.title),
              <.p(^.className :="text", alert.message)
              )
            }).toVdomArray
            )

          }),
          alertsPot.renderEmpty(<.div(^.id :="empty-alert"))

        )
      }
    })
    .build

  def apply(): VdomElement = component(Props())
}
