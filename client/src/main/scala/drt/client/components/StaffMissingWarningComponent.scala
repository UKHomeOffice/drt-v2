package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{LoggedInUser, StaffEdit}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._


object StaffMissingWarningComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    terminalStaffMinutes: Map[Long, StaffMinute],
                    loggedInUser: LoggedInUser,
                    router: RouterCtl[Loc],
                    terminalPageTab: TerminalPageTabLoc
                  )

  val component = ScalaComponent.builder[Props]("StaffMissingWarning")
    .render_P(p => {

      val hasStaff = p.terminalStaffMinutes.values.exists(_.available > 0)
      if (!hasStaff)
        <.span(^.className := "has-alerts",
          <.span(^.`class` := s"alert alert-class-warning the-alert staff-alert", ^.role := "alert",
            <.strong(s"You have not entered any staff"),
            " for the time period you are viewing, please enter staff on the ",
            if (p.loggedInUser.hasRole(StaffEdit))
              p.router.link(
                p.terminalPageTab.copy(
                  mode = "staffing",
                  subMode = "15",
                  queryParams = Map()
                )
              )("Monthly Staffing")
            else
              <.strong("Monthly Staffing"),
            " page."
          )
        )
      else <.span()

    })


    .build

  def apply(
             terminalStaffMinutes: Map[Long, StaffMinute],
             loggedInUser: LoggedInUser,
             router: RouterCtl[Loc],
             terminalPageTab: TerminalPageTabLoc
           ): VdomElement = component(Props(terminalStaffMinutes, loggedInUser, router, terminalPageTab))
}
