package drt.client.components

import drt.client.SPAMain
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared.SDateLike
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.{Anchor, Div}
import uk.gov.homeoffice.drt.auth.LoggedInUser

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
                   whatUserDoing: String,
                   whatWentWrong: String,
                   whatToImprove: String,
                   contactMe: Boolean,
                   isPositive: Boolean,
                   showDialogue: Boolean = false
                  )

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.url, p.selectedDate.millisSinceEpoch))

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedbackComponent")
    .initialStateFromProps(p => State(
      url = p.url,
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
                  <.div( ^.`class` := "col-md-7 ml-auto",
                    <.input.checkbox(^.checked := state.contactMe, ^.onChange ==> ((e: ReactEventFromInput) => setContactMe(e.target.checked)),^.id := "check-contactMe") ,
                    <.label(^.`for` := "check-contactMe","are you happy for us to contact you ?"),
                 )
                ),
                <.br()
              ),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.a("Submit", ^.className := "btn btn-default",
                      ^.href := SPAMain.absoluteUrl(s"email/feedback?negative"),
                      ^.target := "_blank",
                      ^.onClick --> {
                        Callback(GoogleEventTracker.sendEvent(props.url, "click", "Feedback", f""))
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

      def showPositiveFeedbackDialog: VdomTagOf[Div] = {
        <.div(^.className := "modal " + showClass, ^.id := "#positiveFeedback", ^.tabIndex := -1, ^.role := "dialog",
          <.div(^.className := "modal-dialog modal-sm modal-dialog-centered", ^.role := "document",
            <.div(^.className := "modal-content",
              <.div(^.className := "modal-body", <.span("Thank you for feedback !")),
              <.div(^.className := "modal-footer",
                <.div(
                  <.div(^.className := "feedback-links",
                    <.a("Submit",
                      ^.className := "btn btn-default",
                      ^.href := SPAMain.absoluteUrl(s"email/feedback?positive"),
                      ^.target := "_blank",
                      ^.onClick --> {
                        Callback(GoogleEventTracker.sendEvent(props.url, "click", "Feedback", f""))
                      }
                    ),
                    <.button(
                      ^.className := "btn btn-link",
                      VdomAttr("data-dismiss") := "modal", "Close",
                      ^.onClick --> scope.modState(_.copy(showDialogue = false))
                    )
                  )
                )
              )
            )
          ))
      }

      def feedBackButton(icon: Icon.Icon, target: String,btnColor:String): VdomTagOf[Anchor] = {
        <.a(
          icon,
          ^.className := s"btn btn-default $btnColor",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := s"#$target",
          ^.onClick --> scope.modState(_.copy(showDialogue = true))
        )
      }

      if (props.isPositive == Positive) {
        <.div(feedBackButton(Icon.thumbsOUp, "positiveFeedback","btn-success"),
          showPositiveFeedbackDialog())
      } else {
        <.div(feedBackButton(Icon.thumbsODown, "negativeFeedback","btn-danger"),
          showNegativeFeedbackDialog())
      }

    })
    .configure(Reusability.shouldComponentUpdate)
    .build


  def apply(url: String, selectedDate: SDateLike, loggedInUser: LoggedInUser, isPositive: Feedback): VdomElement = component(Props(url, selectedDate, loggedInUser: LoggedInUser, isPositive))
}
