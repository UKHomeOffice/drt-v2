package drt.client.components

import drt.client.SPAMain.Loc
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{AirportConfig, AirportConfigs, LoggedInUser, PortCode, Role}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom

object RestrictedAccessByPortPage {
  val allAirportConfigsToDisplay: List[AirportConfig] = AirportConfigs.allPortConfigs diff AirportConfigs.testPorts
  val allPorts: List[PortCode] = AirportConfigs.allPortConfigs.map(config => config.portCode)
  val urlLowerCase: String = dom.document.URL.toLowerCase
  val portRequested: PortCode = allPorts
    .find(port => urlLowerCase.contains(s"${port.toString.toLowerCase}"))
    .getOrElse(PortCode("InvalidPortCode"))

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet

  def userCanAccessPort(loggedInUser: LoggedInUser, portCode: PortCode): Boolean = AirportConfigs.
    allPortConfigs
    .find(_.portCode == portCode)
    .exists(c => loggedInUser.hasRole(c.role))

  case class Props(loggedInUser: LoggedInUser, ctl: RouterCtl[Loc])

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class State(title: Option[String] = None, message: Option[String] = None, expiryDateTime: Option[MillisSinceEpoch] = None)

  def url(port: PortCode) = urlLowerCase.replace(portRequested.toString.toLowerCase, port.toString.toLowerCase)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("RestrictedAccessForPort")
    .render_P(props => {

      val portsAccessible: Set[PortCode] = allPortsAccessible(props.loggedInUser.roles)
      <.div(^.className := "access-restricted",
        <.span(
          <.h2(^.id := "access-restricted", "Access Restricted"),
          <.div(
            <.p(^.id := "email-for-access", s"You do not currently have permission to access $portRequested. If you would like access to this port, " +
              "please contact us to request access."),
            <.p(
              "Once your request has been processed, please ", <.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                ^.onClick --> Callback(GoogleEventTracker.sendEvent(portRequested.toString, "Log Out from Access Restricted Page", props.loggedInUser.id))),
              " and login again to update your permissions."
            ),
            <.h3("Contact Details"),
            ContactDetailsComponent(),

            if (portsAccessible.nonEmpty) {
              <.div(^.id := "alternate-ports",
                <.p("Alternatively you are able to access the following ports"),
                <.ul(
                  portsAccessible.map(port =>
                    <.li(^.key := port.toString, <.a(^.id := s"$port-link", port.toString, ^.href := url(port)))
                  ).toVdomArray
                )
              )
            } else TagMod()
          )
        )
      )
    })
    .build

  def apply(loggedInUser: LoggedInUser, ctl: RouterCtl[Loc] ): VdomElement = component(Props(loggedInUser, ctl))
}
