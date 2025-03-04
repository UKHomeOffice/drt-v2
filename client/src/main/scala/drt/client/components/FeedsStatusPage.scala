package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.{RequestForecastRecrunch, RequestMissingHistoricSplits, RequestMissingPaxNos, RequestRecalculateArrivals}
import drt.client.components.ToolTips._
import drt.client.components.styles.DrtTheme
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.CheckFeed
import drt.shared.CrunchApi.MillisSinceEpoch
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.RefreshOutlined
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{PortFeedUpload, SuperAdmin}
import uk.gov.homeoffice.drt.feeds.{FeedStatusFailure, FeedStatusSuccess, FeedStatuses}
import uk.gov.homeoffice.drt.ports._


object FeedsStatusPage {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(loggedInUserPot: Pot[LoggedInUser], airportConfigPot: Pot[AirportConfig])

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("StatusPage")
    .render_P { props =>

      val modelRcp = SPACircuit.connect(_.feedStatuses)

      def checkFeed(feedSource: FeedSource): Callback = Callback {
        SPACircuit.dispatch(CheckFeed(feedSource))
      }

      def requestForecastRecrunch(): Callback = Callback {
        SPACircuit.dispatch(RequestForecastRecrunch(recalculateSplits = false))
      }

      def requestSplitsRefresh(): Callback = Callback {
        SPACircuit.dispatch(RequestForecastRecrunch(recalculateSplits = true))
      }

      def requestRecalculateArrivals(): Callback = Callback {
        SPACircuit.dispatch(RequestRecalculateArrivals)
      }

      def requestMissingHistoricSplitsLookup(): Callback = Callback {
        SPACircuit.dispatch(RequestMissingHistoricSplits)
      }

      def requestMissingPaxNos(): Callback = Callback {
        SPACircuit.dispatch(RequestMissingPaxNos)
      }

      modelRcp { proxy =>

        val statuses = proxy()

        val statusContentPot = for {
          allFeedStatuses <- statuses
          user <- props.loggedInUserPot
          airportConfig <- props.airportConfigPot
        } yield {
          val isLiveFeedAvailable = allFeedStatuses.count(_.feedSource == LiveFeedSource) > 0

          val allFeedStatusesSeq = allFeedStatuses.filter(_.feedSource == ApiFeedSource) ++ allFeedStatuses.filterNot(_.feedSource == ApiFeedSource)

          val isCiriumAsPortLive = airportConfig.noLivePortFeed && airportConfig.aclDisabled

          allFeedStatusesSeq.map(feed => {
            val ragStatus = FeedStatuses.ragStatus(SDate.now().millisSinceEpoch, feed.feedSource.maybeLastUpdateThreshold, feed.feedStatuses)

            val manualCheckAllowed = feed.feedSource == AclFeedSource && user.hasRole(PortFeedUpload)

            <.div(^.className := s"feed-status $ragStatus",
              if (feed.feedSource.name == "API")
                <.h3(<.div(^.className := "flex-horizontally", feed.feedSource.displayName, apiDataTooltip))
              else if (manualCheckAllowed)
                <.h3(feed.feedSource.displayName, " ", MuiButton(variant = "outlined", size = "medium", color = Color.primary)(MuiIcons(RefreshOutlined)(), ^.onClick --> checkFeed(feed.feedSource)))
              else if (isCiriumAsPortLive)
                <.h3("Live arrival")
              else
                <.h3(feed.feedSource.displayName),
              if (isCiriumAsPortLive)
                <.div(^.className := s"feed-status-description", <.p(feed.feedSource.description(isCiriumAsPortLive)))
              else
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
              },
              <.div(^.className := "clear"),
              <.h4("Recent connections"),
              <.ul(
                feed.feedStatuses.statuses.sortBy(_.date).reverseMap {
                  case FeedStatusSuccess(date, updates) => <.li(s"${displayTime(date)}: $updates updates")
                  case FeedStatusFailure(date, _) => <.li(s"${displayTime(date)}: Connection failed")
                }.toVdomArray
              )
            )
          }).toVdomArray
        }

        val crunchControlsPot = for {
          user <- props.loggedInUserPot
        } yield {
          if (user.hasRole(SuperAdmin)) <.div(
            <.br(),
            <.h2("Crunch"),
            <.div(^.className := "crunch-actions-container",
              ThemeProvider(DrtTheme.theme)(
                MuiButton(variant = "outlined", color = Color.primary)(
                  <.div("Re-crunch forecast", ^.onClick --> requestForecastRecrunch())
                ),
                MuiButton(variant = "outlined", color = Color.primary)(
                  <.div("Refresh splits", ^.onClick --> requestSplitsRefresh())
                ),
                MuiButton(variant = "outlined", color = Color.primary)(
                  <.div("Recalculate arrivals", ^.onClick --> requestRecalculateArrivals())
                ),
                MuiButton(variant = "outlined", color = Color.primary)(
                  <.div("Lookup missing historic splits", ^.onClick --> requestMissingHistoricSplitsLookup())
                ),
                MuiButton(variant = "outlined", color = Color.primary)(
                  <.div("Lookup missing forecast pax nos", ^.onClick --> requestMissingPaxNos())
                ),
              )
            )
          ) else EmptyVdom
        }

        <.div(
          <.h2("Feeds status"),
          <.div(^.className := "feed-status-container",
            statusContentPot.getOrElse(EmptyVdom)
          ),
          crunchControlsPot.getOrElse(EmptyVdom)
        )
      }
    }
    .build

  private def displayTime(date: MillisSinceEpoch): String = {
    val dateToDisplay = SDate(date)
    if (dateToDisplay.toISODateOnly == SDate.now().toISODateOnly) dateToDisplay.hms
    else dateToDisplay.prettyDateTime
  }

  private def timeAgo(millisToCheck: MillisSinceEpoch): String = {
    val minutes = (SDate.now().millisSinceEpoch - millisToCheck) / 60000
    val hours = minutes / 60
    val days = hours / 24

    if (minutes < 1) s"< 1 min ago"
    else if (minutes <= 60) s"$minutes mins ago"
    else if (hours <= 24) s"$hours hrs ago"
    else s"$days days ago"
  }

  def apply(loggedInUserPot: Pot[LoggedInUser], airportConfigPot: Pot[AirportConfig]): VdomElement = component(Props(loggedInUserPot, airportConfigPot))
}
