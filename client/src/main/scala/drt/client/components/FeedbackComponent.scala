package drt.client.components

import drt.client.actions.Actions.{SaveAlert, SetAlerts}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.{DrtApi, SPACircuit}
import drt.client.services.JSDateConversions.SDate
import drt.shared.{Alert, SDateLike}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, VdomTagOf, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.{Anchor, Div, Span}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import upickle.default.{macroRW, write, ReadWriter => RW}


sealed trait Feedback

case object Positive extends Feedback

case object Negative extends Feedback

object FeedbackComponent {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(url: String,
                   selectedDate: SDateLike,
                   loggedInUser: LoggedInUser,
                   isPositive: Feedback)

  case class State(url: String,
                   feedbackUserEmail: String,
                   whatUserDoing: String,
                   whatWentWrong: String,
                   whatToImprove: String,
                   contactMe: Boolean,
                   isPositive: Boolean,
                   showDialogue: Boolean = false
                  )

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.url, p.selectedDate.millisSinceEpoch))
  implicit val rw: RW[State] = macroRW

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedbackComponent")
    .initialStateFromProps(p => State(
      url = p.url,
      feedbackUserEmail = p.loggedInUser.email,
      whatUserDoing = "",
      whatWentWrong = "",
      whatToImprove = "",
      contactMe = false,
      isPositive = p.isPositive == Positive
    ))
    .renderPS((scope, props, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      def setWhatUserDoing(whatUserDoing: String) = scope.modState(state => {
        state.copy(whatUserDoing = whatUserDoing)
      })

      def setWhatWentWrong(whatWentWrong: String) = scope.modState(state => {
        state.copy(whatWentWrong = whatWentWrong)
      })

      def setWhatToImprove(whatToImprove: String) = scope.modState(state => {
        state.copy(whatToImprove = whatToImprove)
      })

      def setContactMe(contactMe: Boolean) = scope.modState(state => {
        state.copy(contactMe = contactMe)
      })

      def showNegativeFeedbackDialog: VdomTagOf[Div] = {
        <.div(^.className := "modal " + showClass, ^.id := "#negativeFeedback", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header",
                <.h5(^.className := "modal-title", "Thank you for feedback !")
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
                  <.div(^.`class` := "col-md-7 ml-auto",
                    <.ul( ^.className := "nav navbar-nav navbar-left",
                    <.li(<.input.checkbox(^.checked := state.contactMe, ^.onChange ==> ((e: ReactEventFromInput) => setContactMe(e.target.checked)), ^.id := "check-contactMe")),
                    <.li(),
                    <.li(<.label(^.`for` := "check-contactMe", " are you happy for us to contact you?"))
                    )
                  )
                ),
                <.br()
              ),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.button("Submit", ^.className := "btn btn-default",
                      ^.onClick --> {
                        scope.modState(_.copy(showDialogue = false), Callback(DrtApi.post("email/feedback/negative", write(state))))
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

      if (props.isPositive == Positive) {
        if (scope.state.showDialogue)
         SPACircuit.dispatch(SetAlerts(List(
           Alert("","Thanks for your feedback. This helps us improve the service.",
             "notice",
             expires = System.currentTimeMillis() + 5000)),
           System.currentTimeMillis()))
        <.div(
          <.button(Icon.thumbsOUp,
            ^.className := "btn btn-default btn-success",
            ^.onClick --> scope.modState(_.copy(showDialogue = true), Callback(DrtApi.post("email/feedback/positive", write(state))))
          )
        )

      } else {
        <.div(feedBackButton(Icon.thumbsODown, "negativeFeedback", "btn-danger"),
          showNegativeFeedbackDialog())
      }

    })
    .configure(Reusability.shouldComponentUpdate)
    .build


  def apply(url: String, selectedDate: SDateLike, loggedInUser: LoggedInUser, isPositive: Feedback): VdomElement = component(Props(url, selectedDate, loggedInUser: LoggedInUser, isPositive))
}
