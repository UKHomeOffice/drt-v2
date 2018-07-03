package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.SPACircuit
import drt.shared.FeedStatuses
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

object StatusPage {

  val log: Logger = LoggerFactory.getLogger(getClass().getName)

  case class Props()

  val component = ScalaComponent.builder[Props]("StatusPage")
    .render_P(_ => {

      val feedsRCP = SPACircuit.connect(_.feedStatuses)

      feedsRCP { feedsMP =>
        <.div(
          <.ul(
            feedsMP().render((statuses: FeedStatuses) => {
              statuses.statuses.map(fs => {
                log.info(s"feed status: ${fs.name}")
                <.li(s"feed ${fs.name}")
              }).toVdomArray
            })
          )
        )
      }
    })
    .build

  def apply(): VdomElement = component(Props())
}
