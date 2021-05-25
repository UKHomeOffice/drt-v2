package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.DrtApi
import io.kinoplan.scalajs.react.material.ui.core.MuiSnackbar
import io.kinoplan.scalajs.react.material.ui.core.internal.Origin
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, VdomTagOf, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEvent, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.{Anchor, Div}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import upickle.default.{macroRW, write, ReadWriter => RW}

import scala.scalajs.js

object NegativeFeedbackComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String,
                   loggedInUser: LoggedInUser)

  case class State(url: String,
                   feedbackUserEmail: String,
                   whatUserDoing: String,
                   whatWentWrong: String,
                   whatToImprove: String,
                   contactMe: Boolean,
                   showDialogue: Boolean,
                   showAcknowledgement: Boolean) {
    def hideAcknowledgement: State = copy(showAcknowledgement = false)
  }

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by(_.url)
  implicit val rw: RW[State] = macroRW

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedbackComponent")
    .initialStateFromProps(p => State(
      url = p.url,
      feedbackUserEmail = p.loggedInUser.email,
      whatUserDoing = "",
      whatWentWrong = "",
      whatToImprove = "",
      contactMe = false,
      showDialogue = false,
      showAcknowledgement = false
    ))
    .renderS((scope, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      def setWhatUserDoing(whatUserDoing: String) = scope.modState(_.copy(whatUserDoing = whatUserDoing))

      def setWhatWentWrong(whatWentWrong: String) = scope.modState(_.copy(whatWentWrong = whatWentWrong))

      def setWhatToImprove(whatToImprove: String) = scope.modState(_.copy(whatToImprove = whatToImprove))

      def setContactMe(contactMe: Boolean) = scope.modState(_.copy(contactMe = contactMe))

      def negativeFeedbackDialog: VdomTagOf[Div] = {
        <.div(^.className := "modal " + showClass, ^.id := "#negativeFeedback", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header",
                <.h5(^.className := "modal-title", "Thanks for your feedback. This helps us improve the service.")
              ),
              <.br(),
              <.div(^.className := "modal-body",
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatUserDoing-text", "What were you doing?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatUserDoing-text", ^.placeholder := "Please write your comments here ...", ^.`class` := "col-md-7", ^.value := state.whatUserDoing, ^.onChange ==> ((e: ReactEventFromInput) => setWhatUserDoing(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatWentWrong-text", "What went wrong?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatWentWrong-text", ^.placeholder := "Please write your comments here ...", ^.`class` := "col-md-7", ^.value := state.whatWentWrong, ^.onChange ==> ((e: ReactEventFromInput) => setWhatWentWrong(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatToImprove-text", "What can we improve?", ^.`class` := "col-md-4"),
                  <.textarea(^.id := "whatToImprove-text", ^.placeholder := "Please write your suggestion here ....", ^.`class` := "col-md-7", ^.value := state.whatToImprove, ^.onChange ==> ((e: ReactEventFromInput) => setWhatToImprove(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.div(^.`class` := "col-md-10 ml-auto",
                    <.ul(^.className := "nav navbar-nav navbar-left",
                      <.li(<.input.checkbox(^.checked := state.contactMe, ^.onChange ==> ((e: ReactEventFromInput) => setContactMe(e.target.checked)), ^.id := "check-contactMe")),
                      <.li(<.label(^.style := js.Dictionary("margin-left" -> "5px"), ^.`for` := "check-contactMe", "Are you happy for us to contact you regarding your feedback?"))
                    ))
                )
              ),
              <.br(),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.button("Submit", ^.className := "btn btn-default",
                      ^.onClick --> {
                        scope.modState(
                          _.copy(showDialogue = false, showAcknowledgement = true),
                          Callback(DrtApi.post("email/feedback/negative", write(state))))
                      }
                    ),
                    <.button(^.className := "btn btn-link",
                      VdomAttr("data-dismiss") := "modal", "Close",
                      ^.onClick --> scope.modState(_.copy(showDialogue = false))
                    )
                  )
                )
              )
            )
          )
        )
      }

      def feedBackButton(icon: Icon.Icon, target: String, btnColor: String): VdomTagOf[Anchor] = {
        <.a(
          icon,
          ^.className := s"btn btn-default $btnColor",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := s"#$target",
          ^.onClick --> scope.modState(_.copy(showDialogue = true))
        )
      }

      def handleClose: (ReactEvent, String) => Callback = (_, reason) => {
        scope.modState(_.hideAcknowledgement).when_(reason != "clickaway")
      }

      val acknowledgementMessage = MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
        autoHideDuration = 5000,
        message = <.div(^.className := "muiSnackBar", "Thanks for your feedback. This helps us improve the service."),
        open = scope.state.showAcknowledgement,
        onClose = handleClose)()

      <.div(
        acknowledgementMessage,
        feedBackButton(Icon.thumbsODown, "negativeFeedback", "btn-danger"),
        negativeFeedbackDialog())

    })
    .configure(Reusability.shouldComponentUpdate)
    .build


  def apply(url: String, loggedInUser: LoggedInUser): VdomElement = component(Props(url, loggedInUser: LoggedInUser))
}
