package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.DrtApi
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.internal.Origin
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEvent, Reusability, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import upickle.default.{macroRW, write, ReadWriter => RW}


object PositiveFeedbackComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String,
                   loggedInUser: LoggedInUser,
                  )

  case class State(url: String,
                   feedbackUserEmail: String,
                   showAcknowledgement: Boolean = false) {
    def hideAcknowledgement: State = copy(showAcknowledgement = false)
  }

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by(_.url)
  implicit val rw: RW[State] = macroRW

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("NegativeFeedbackComponent")
    .initialStateFromProps(p => State(
      url = p.url,
      feedbackUserEmail = p.loggedInUser.email
    ))
    .renderS((scope, state) => {

      def handleClose: (ReactEvent, String) => Callback = (_, reason) => {
        scope.modState(_.hideAcknowledgement).when_(reason != "clickaway")
      }

      val acknowledgementMessage = MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
        autoHideDuration = 5000,
        message = <.div(^.className := "muiSnackBar", "Thanks for your feedback. This helps us improve the service."),
        open = scope.state.showAcknowledgement,
        onClose = handleClose)()

      <.div(
        <.div(acknowledgementMessage),
        <.button(Icon.thumbsOUp,
          ^.className := "btn btn-default btn-success",
          ^.onClick --> scope.modState(_.copy(showAcknowledgement = true), Callback(DrtApi.post("email/feedback/positive", write(state))))
        )
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(url: String, loggedInUser: LoggedInUser): VdomElement = component(Props(url, loggedInUser: LoggedInUser))
}
