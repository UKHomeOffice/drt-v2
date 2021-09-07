package drt.client.components

import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}


object StatusPage {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("StatusPage")
    .render_P { _ =>

      val feedStatusesRCP = SPACircuit.connect(_.feedStatuses)

      feedStatusesRCP { feedStatusesMP =>
        val feedStatusesPot = feedStatusesMP()

        <.div(
          <.h2("Feeds status"),
          feedStatusesPot.render((allFeedStatuses: Seq[FeedSourceStatuses]) => {
            val isLiveFeedAvailable = allFeedStatuses.count(_.feedSource == LiveFeedSource) > 0

            val allFeedStatusesSeq = allFeedStatuses.filter(_.feedSource == ApiFeedSource) ++ allFeedStatuses.filterNot(_.feedSource == ApiFeedSource)

            allFeedStatusesSeq.map(feed => {
              val ragStatus = FeedStatuses.ragStatus(SDate.now().millisSinceEpoch, feed.feedSource.maybeLastUpdateThreshold, feed.feedStatuses)

              <.div(^.className := s"feed-status $ragStatus",
              if (feed.feedSource.name == "API")
                <.h3(feed.feedSource.name, " ", apiDataTooltip)
              else
                <.h3(feed.feedSource.name)

              ,
                <.div(^.className := s"feed-status-description", <.p(feed.feedSource.description(isLiveFeedAvailable))),
              {
                val times = Seq(
                  (("Updated", "When we last received new data"), feed.feedStatuses.lastUpdatesAt),
                  (("Checked", "When we last checked for new data"), feed.feedStatuses.lastSuccessAt),
                  (("Failed", "When we last experienced a failure with the feed"), feed.feedStatuses.lastFailureAt)
                )
                <.ul(^.className := "times-summary", times.zipWithIndex.map {
                  case (((label, description), maybeSDate), idx) =>
                    val className = if (idx == times.length - 1) "last" else ""
                    <.li(
                      ^.className := className,
                      <.div(^.className := "vert-align",
                      <.div(<.div(Tippy.describe(<.span(description), <.h4(label))), <.div(s"${
                      maybeSDate.map(lu => s"${timeAgo(lu)}").getOrElse("n/a")
                    }"))))
                }.toVdomArray)
              }
              ,
              <.div(^.className := "clear")
              ,
              <.h4("Recent connections")
              ,
              <.ul(
                feed.feedStatuses.statuses.sortBy(_.date).reverseMap {
                  case FeedStatusSuccess(date, updates) => <.li(s"${displayTime(date)}: $updates updates")
                  case FeedStatusFailure(date, _) => <.li(s"${displayTime(date)}: Connection failed")
                }.toVdomArray
              )
              )
            }).toVdomArray
          })
        )
      }
    }
    .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("feed-status")))
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

    if (minutes < 1) s"< 1 min ago"
    else if (minutes <= 60) s"$minutes mins ago"
    else if (hours <= 24) s"$hours hrs ago"
    else s"$days days ago"
  }

  def apply(): VdomElement = component(Props())
}
