package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom


object EnvironmentWarningComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component = ScalaComponent.builder[Props]("EnvironmentWarning")
    .render_P(_ => {

      val env = envFromUrl(dom.document.location.href)

      <.span(^.className := "has-alerts",

        if (env == "PROD") EmptyVdom else
          <.span(^.`class` := s"alert alert-class-warning the-alert", ^.role := "alert",
            s"You are currently viewing $env"
          )
      )

    })
    .build

  def apply(): VdomElement = component(Props())

  def envFromUrl(url: String) = url match {
    case produrl if produrl contains ".drt.homeoffice" => "PROD"
    case preprodurl if preprodurl contains ".drt-preprod.homeoffice" => "PREPROD"
    case stagingurl if stagingurl contains ".drt-staging.homeoffice" => "STAGING"
    case _ => "TEST"
  }
}
