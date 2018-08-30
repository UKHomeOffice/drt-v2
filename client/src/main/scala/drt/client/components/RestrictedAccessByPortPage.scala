package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{AirportConfigs, Role}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import org.scalajs.dom

object RestrictedAccessByPortPage {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class State(title: Option[String] = None, message: Option[String] = None, expiryDateTime: Option[MillisSinceEpoch] = None)

  val component = ScalaComponent.builder[Unit]("Restricted Access On Port")
    .render(_ => {
      val modelRCP = SPACircuit.connect(m => m.loggedInUserPot)

      val allAirportConfigs = AirportConfigs.allPorts diff AirportConfigs.testPorts
      val allPorts = allAirportConfigs.map(config => config.portCode.toLowerCase)
      val urlLowerCase = dom.document.URL.toLowerCase
      val portRequested = allPorts.find(port => urlLowerCase.contains(s"/$port/")).map(_.toUpperCase).getOrElse("unknown port code")

      def allPortsAccessible(roles: Set[Role]): Set[String] = allAirportConfigs.filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet

      def url(port: String) = urlLowerCase.replace(s"/${portRequested.toLowerCase}/", s"/${port.toLowerCase}/")

      GoogleEventTracker.sendPageView(s"$portRequested-access-restricted")

      <.div(
        modelRCP(modelPotMP => {
          val loggedInUserPot = modelPotMP()
          <.span(
            <.h2(^.id:="access-restricted", "Access Restricted"),
            loggedInUserPot.render(loggedInUser => {
              val portsAccessible: Set[String] = allPortsAccessible(loggedInUser.roles)
              <.div(
                <.p(^.id:="email-for-access", s"You do not have access to your desired port $portRequested. If you would like access to this port, " +
                  "please ", <.a("click here to send an email", ^.href := s"mailto:drtdevteam@digital.homeoffice.gov.uk;drtenquiries@homeoffice.gov.uk?subject=request access to port $portRequested for ${loggedInUser.email}&body=I, ${loggedInUser.email}, would like to request access to $portRequested."), " to request access."),
                if (portsAccessible.nonEmpty) {
                  <.div(^.id:="alternate-ports",
                    <.p("Alternatively you are able to access the following ports"),
                    <.ul(
                      portsAccessible.map(port =>
                        <.li(^.key := port, <.a(^.id:=s"$port-link", port, ^.href := url(port)))
                      ).toVdomArray
                    )
                  )
                } else ""
              )
            }

            )

          )
        })
      )
    })
    .build

  def apply(): VdomElement = component()
}
