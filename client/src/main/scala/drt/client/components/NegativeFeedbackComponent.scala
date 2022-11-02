package drt.client.components

import diode.UseValueEq
import diode.data.Ready
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.{DrtApi, SPACircuit}
import drt.shared.NegativeFeedback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, VdomTagOf, ^, _}
import japgolly.scalajs.react.{Callback, CallbackTo, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.{Anchor, Div}
import upickle.default.write

object NegativeFeedbackComponent {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String, userEmail: String) extends UseValueEq

  case class State(feedback: NegativeFeedback,
                   showDialogue: Boolean) extends UseValueEq

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedbackComponent")
    .initialStateFromProps(p => State(
      NegativeFeedback(
        url = p.url,
        feedbackUserEmail = p.userEmail,
        whatUserDoing = "",
        whatWentWrong = "",
        whatToImprove = ""
      ),
      showDialogue = false,
    ))
    .renderS((scope, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      def setWhatUserDoing(whatUserDoing: String): CallbackTo[Unit] = scope.modState(_.copy(feedback = state.feedback.copy(whatUserDoing = whatUserDoing)))

      def setWhatWentWrong(whatWentWrong: String): CallbackTo[Unit] = scope.modState(_.copy(feedback = state.feedback.copy(whatWentWrong = whatWentWrong)))

      def setWhatToImprove(whatToImprove: String): CallbackTo[Unit] = scope.modState(_.copy(feedback = state.feedback.copy(whatToImprove = whatToImprove)))

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
                  <.textarea(
                    ^.id := "whatUserDoing-text",
                    ^.placeholder := "Please write your comments here ...",
                    ^.`class` := "col-md-7",
                    ^.value := state.feedback.whatUserDoing,
                    ^.onChange ==> ((e: ReactEventFromInput) => setWhatUserDoing(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatWentWrong-text", "What went wrong?", ^.`class` := "col-md-4"),
                  <.textarea(
                    ^.id := "whatWentWrong-text",
                    ^.placeholder := "Please write your comments here ...",
                    ^.`class` := "col-md-7",
                    ^.value := state.feedback.whatWentWrong,
                    ^.onChange ==> ((e: ReactEventFromInput) => setWhatWentWrong(e.target.value))),
                ),
                <.br(),
                <.div(^.`class` := "row",
                  <.label(^.`for` := "whatToImprove-text", "What can we improve?", ^.`class` := "col-md-4"),
                  <.textarea(
                    ^.id := "whatToImprove-text",
                    ^.placeholder := "Please write your suggestion here ....",
                    ^.`class` := "col-md-7",
                    ^.value := state.feedback.whatToImprove,
                    ^.onChange ==> ((e: ReactEventFromInput) => setWhatToImprove(e.target.value))),
                ),
              ),
              <.br(),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.button("Submit", ^.className := "btn btn-default",
                      ^.onClick --> {
                        scope.modState(
                          _.copy(showDialogue = false),
                          Callback(DrtApi.post("email/feedback/negative", write(state.feedback))) >>
                            Callback(SPACircuit.dispatch(SetSnackbarMessage(Ready("Thanks for your feedback. This helps us improve the service.")))))
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
          ^.onClick --> scope.modState(_.copy(showDialogue = true)),
          ^.aria.label := "Negative feedback",
          ^.tabIndex := 0
        )
      }

      <.div(^.className := "feedback",
        feedBackButton(Icon.thumbsODown, "negativeFeedback", "btn-danger"),
        negativeFeedbackDialog())

    })
    .build


  def apply(url: String, userEmail: String): VdomElement = component(Props(url, userEmail))
}
