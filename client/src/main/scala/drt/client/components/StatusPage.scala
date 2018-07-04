package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.{FeedStatusFailure, FeedStatusSuccess, FeedStatuses}
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
          <.h2("Feed statuses"),
          feedsMP().render((allFeedStatuses: Seq[FeedStatuses]) => {
            allFeedStatuses.map(feed =>
              <.div(
                <.h2(feed.name),
                <.ul(
                  <.li(s"Last successful connection: ${feed.lastSuccessAt.map(lu => SDate(lu).prettyDateTime()).getOrElse("n/a")}"),
                  <.li(s"Last updates: ${feed.lastUpdatesAt.map(lu => SDate(lu).prettyDateTime()).getOrElse("n/a")}"),
                  <.li(s"Last failed connection: ${feed.lastFailureAt.map(lu => SDate(lu).prettyDateTime()).getOrElse("n/a")}")
                ),
                <.ul(
                  feed.statuses.sortBy(_.date).reverse.map {
                    case FeedStatusSuccess(date, updates) => <.li(s"${SDate(date).hms()}: $updates updates")
                    case FeedStatusFailure(date, msg) => <.li(s"${SDate(date).hms()}: Connection failed")
                  }.toVdomArray
                )
              )
            ).toVdomArray
          })
        )
      }
    })
    .build

  def apply(): VdomElement = component(Props())
}
