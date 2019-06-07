package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{AirportConfig, AirportConfigs, LoggedInUser, Role}
import japgolly.scalajs.react.extra.router.BaseUrl
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom

object RestrictedAccessByPortPage {


  val allAirportConfigsToDisplay: List[AirportConfig] = AirportConfigs.allPorts diff AirportConfigs.testPorts
  val allPorts: List[String] = AirportConfigs.allPorts.map(config => config.portCode.toLowerCase)
  val urlLowerCase: String = dom.document.URL.toLowerCase
  val portRequested: String = allPorts.find(port => urlLowerCase.contains(s"$port"))
    .map(_.toUpperCase).getOrElse("[please specify port code]")

  def allPortsAccessible(roles: Set[Role]): Set[String] = AirportConfigs.allPorts
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet

  def userCanAccessPort(loggedInUser: LoggedInUser, portCode: String): Boolean = AirportConfigs.
    allPorts
    .find(_.portCode == portCode)
    .exists(c => loggedInUser.hasRole(c.role))

  case class Props(loggedInUser: LoggedInUser)

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class State(title: Option[String] = None, message: Option[String] = None, expiryDateTime: Option[MillisSinceEpoch] = None)

  val component = ScalaComponent.builder[Props]("RestrictedAccessForPort")
    .render_P(props => {

      def url(port: String) = urlLowerCase.replace(s"/${portRequested.toLowerCase}/", s"/${port.toLowerCase}/")

      val portsAccessible: Set[String] = allPortsAccessible(props.loggedInUser.roles)
      <.div(^.className := "access-restricted",
        <.span(
          <.h2(^.id := "access-restricted", "Access Restricted"),
          <.div(
            <.p(^.id := "email-for-access", s"You do not currently have permission to access $portRequested. If you would like access to this port, " +
              "please ", <.a("click here to request access by email",
              ^.onClick --> Callback(GoogleEventTracker.sendEvent(portRequested, "Request for port access", props.loggedInUser.id)),
              ^.href :=
              s"mailto:drtdevteam@digital.homeoffice.gov.uk;drtenquiries@homeoffice.gov.uk?subject=request" +
                s"Please give me access to $portRequested on DRT&body=Please give me access to DRT $portRequested."), "."),
            <.p(
              "Once your request has been processed, please ", <.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                ^.onClick --> Callback(GoogleEventTracker.sendEvent(portRequested, "Log Out from Access Restricted Page", props.loggedInUser.id))),
              " and login again to update your permissions."
            ),
            if (portsAccessible.nonEmpty) {
              <.div(^.id := "alternate-ports",
                <.p("Alternatively you are able to access the following ports"),
                <.ul(
                  portsAccessible.map(port =>
                    <.li(^.key := port, <.a(^.id := s"$port-link", port, ^.href := url(port)))
                  ).toVdomArray
                )
              )
            } else TagMod()
          )
        )
      )
    })
    .build

  def apply(loggedInUser: LoggedInUser): VdomElement = component(Props(loggedInUser))
}
