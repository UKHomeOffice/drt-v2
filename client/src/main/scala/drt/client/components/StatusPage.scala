package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{FeedStatusFailure, FeedStatusSuccess, FeedStatuses}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object StatusPage {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component = ScalaComponent.builder[Props]("StatusPage")
    .render_P(_ => {

      val modelRCP = SPACircuit.connect(_.feedStatuses)

      modelRCP { modelMP =>
        val feedStatusesAndCrunchState = modelMP()

        <.div(
          <.h2("Feeds status"),
          feedStatusesAndCrunchState.render((allFeedStatuses: Seq[FeedStatuses]) => {
            allFeedStatuses.map(feed => {
              <.div(^.className := s"feed-status ${feed.ragStatus(SDate.now().millisSinceEpoch)}",
                <.h3(feed.name),
                {
                  val times = Seq(
                    (("Updated", "When we last received new data"), feed.lastUpdatesAt),
                    (("Checked", "When we last checked for new data"), feed.lastSuccessAt),
                    (("Failed", "When we last experienced a failure with the feed"), feed.lastFailureAt)
                  )
                  <.ul(^.className := "times-summary", times.zipWithIndex.map {
                    case (((label, description), maybeSDate), idx) =>
                      val className = if (idx == times.length - 1) "last" else ""
                      <.li(^.className := className, <.div(^.className := "vert-align", <.div(<.div(<.h4(label, ^.title := description)), <.div(s"${
                        maybeSDate.map(lu => s"${timeAgo(lu)}").getOrElse("n/a")
                      }"))))
                  }.toVdomArray)
                },
                <.div(^.className := "clear"),
                <.h4("Recent connections"),
                <.ul(
                  feed.statuses.sortBy(_.date).reverse.map {
                    case FeedStatusSuccess(date, updates) => <.li(s"${displayTime(date)}: $updates updates")
                    case FeedStatusFailure(date, msg) => <.li(s"${displayTime(date)}: Connection failed")
                  }.toVdomArray
                )
              )
            }).toVdomArray
          })
        )
      }
    })
    .componentDidMount(p => Callback(GoogleEventTracker.sendPageView("feed-status")))
    .build

  private def displayTime(date: MillisSinceEpoch): String = {
    val dateToDisplay = SDate(date)
    if (dateToDisplay.toISODateOnly == SDate.now().toISODateOnly) dateToDisplay.hms()
    else dateToDisplay.prettyDateTime()
  }

  def timeAgo(millisToCheck: MillisSinceEpoch): String = {
    val minutes = (SDate.now().millisSinceEpoch - millisToCheck) / 60000
    val hours = minutes / 60
    val days = hours / 24

    if (minutes < 1) s"< 1 min"
    else if (minutes <= 60) s"$minutes mins"
    else if (hours <= 24) s"$hours hrs"
    else s"$days days"
  }

  def apply(): VdomElement = component(Props())
}
