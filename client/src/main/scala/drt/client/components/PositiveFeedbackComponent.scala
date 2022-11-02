package drt.client.components

import diode.data.Ready
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.{DrtApi, SPACircuit}
import drt.shared.PositiveFeedback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import upickle.default.{macroRW, write, ReadWriter => RW}


object PositiveFeedbackComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String, feedbackUserEmail: String)

  implicit val rw: RW[Props] = macroRW

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("NegativeFeedbackComponent")
    .render_P(props => {
      <.div(^.className := "feedback",
        <.button(Icon.thumbsOUp,
          ^.aria.label := "Positive feedback",
          ^.className := "btn btn-default btn-success",
          ^.onClick --> (Callback(DrtApi.post("email/feedback/positive", write(PositiveFeedback(props.feedbackUserEmail, props.url))))
            >> Callback(SPACircuit.dispatch(SetSnackbarMessage(Ready("Thanks for your feedback. This helps us improve the service.")))))
        )
      )
    })
    .build

  def apply(url: String, userEmail: String): VdomElement = component(Props(url, userEmail))
}
