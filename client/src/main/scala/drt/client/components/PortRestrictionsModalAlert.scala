package drt.client.components

import drt.client.actions.Actions.UpdateShowAlertModalDialog
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.LoggedInUser
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}


object PortRestrictionsModalAlert {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(display: Boolean, loggedInUser: LoggedInUser)

  val component = ScalaComponent.builder[Props]("ModalDialog")
    .render_P(props => {

      val currentPort = RestrictedAccessByPortPage.portRequested
      log.info(s"Current port is $currentPort")
      val userCanAccessPort = RestrictedAccessByPortPage.userCanAccessPort(props.loggedInUser, currentPort)
      val show = if (props.display && !userCanAccessPort) "show" else ""
      <.div(^.className := s"modal $show", ^.tabIndex := -1, ^.role := "dialog",
        <.div(^.className := "modal-dialog", ^.role := "document",
          <.div(^.className := "modal-content",
            <.div(^.className := "modal-header",
              <.h5(^.className := "modal-title", "Action required! DRT Permissions are changing.")
            ),
            <.div(^.className := "modal-body",
              <.p("We are adding new restrictions to DRT that will prevent users from viewing ports they are not assigned to."),
              <.p("Currently you have not been assigned access to this port. Please request access by ",
                <.a(^.href := s"mailto:drtpoiseteam@homeoffice.gov.uk?subject=DRT Port Access Request&body=Please give me access to $currentPort", "emailing the DRT Team ASAP"), " to avoid any disruption.")
            ),
            <.div(^.className := "modal-footer",
              <.button(^.className := "btn btn-primary", "Close", ^.onClick --> {
                Callback(SPACircuit.dispatch(UpdateShowAlertModalDialog(false)))
              })
            )
          )
        )
      )
    })
    .componentDidMount(p => Callback(GoogleEventTracker.sendPageView("port-restrictions-warning-modal")))
    .build

  def apply(display: Boolean, loggedInUser: LoggedInUser): VdomElement = component(Props(display, loggedInUser))
}