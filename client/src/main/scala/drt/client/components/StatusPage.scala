package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
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
            allFeedStatuses.map(feed => {
              val rag = if (feed.lastSuccessAt.getOrElse(0L) > feed.lastFailureAt.getOrElse(0L)) "green" else "red"
              <.div(^.className := s"feed-status $rag",
                <.h3(feed.name),
                {
                  val times = Seq(
                    ("Checked", feed.lastSuccessAt),
                    ("Updated", feed.lastUpdatesAt),
                    ("Failed", feed.lastFailureAt)
                  )
                  <.ul(^.className := "times-summary", times.zipWithIndex.map {
                    case ((label, maybeSDate), idx) =>
                      val className = if (idx == times.length - 1) "last" else ""
                      <.li(^.className := className, <.div(^.className := "vert-align", <.div(<.div(label), <.div(s"${maybeSDate.map(lu => s"${minutesAgo(lu)} mins").getOrElse("n/a")}"))))
                  }.toVdomArray)
                },
                <.div(^.className := "clear"),
                <.h4("Recent connections"),
                <.ul(
                  feed.statuses.sortBy(_.date).reverse.map {
                    case FeedStatusSuccess(date, updates) => <.li(s"${SDate(date).hms()}: $updates updates")
                    case FeedStatusFailure(date, msg) => <.li(s"${SDate(date).hms()}: Connection failed")
                  }.toVdomArray
                )
              )
            }).toVdomArray
          })
        )
      }
    })
    .build

  def minutesAgo(millisToCheck: MillisSinceEpoch) = {
    (SDate.now().millisSinceEpoch - millisToCheck) / 60000
  }

  def apply(): VdomElement = component(Props())
}
