package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.spa.TerminalPageModes.Staffing
import drt.shared.CrunchApi.StaffMinute
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit


object StaffMissingWarningComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    terminalStaffMinutes: Map[Long, StaffMinute],
                    loggedInUser: LoggedInUser,
                    router: RouterCtl[Loc],
                    terminalPageTab: TerminalPageTabLoc
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("StaffMissingWarning")
    .render_P { p =>
      val hasStaff = p.terminalStaffMinutes.values.exists(_.available > 0)

      <.span(^.className := "has-alerts grow",
        if (!hasStaff)
          <.span(^.`class` := s"alert alert-class-warning the-alert staff-alert", ^.role := "alert",
            <.strong(s"You have not entered any staff"),
            " for the time period you are viewing, please enter staff on the ",
            if (p.loggedInUser.hasRole(StaffEdit))
              p.router.link(
                p.terminalPageTab.update(mode = Staffing, subMode = "15")
              )("Monthly Staffing")
            else
              <.strong("Monthly Staffing"),
            " page."
          )
        else <.span()
      )
    }
    .build

  def apply(
             terminalStaffMinutes: Map[Long, StaffMinute],
             loggedInUser: LoggedInUser,
             router: RouterCtl[Loc],
             terminalPageTab: TerminalPageTabLoc
           ): VdomElement = component(Props(terminalStaffMinutes, loggedInUser, router, terminalPageTab))
}
