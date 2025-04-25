package drt.client.components

import diode.UseValueEq
import diode.data.{Pending, Pot, Ready}
import drt.client.components.FlightTableComponents.maybeLocalTimeWithPopup
import drt.shared._
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.TableSection
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource}

object ArrivalInfo {

  case class Props(arrivalSources: Pot[List[Option[FeedSourceArrival]]], airportConfig: AirportConfig, paxFeedSourceOrder: List[FeedSource]) extends UseValueEq

  def SourcesTable: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalSourcesTable")
    .render_P { props =>
      props.arrivalSources match {
        case Ready(sources) =>
          <.div(
            <.h2(s"Feed sources for arrival"),
            <.table(^.className := "arrivals-table table-striped",
              tableHead,
              <.tbody(
                sources.collect { case Some(sourceArrival) =>
                  FeedSourceRow.component(FeedSourceRow.Props(sourceArrival, props.airportConfig, props.paxFeedSourceOrder))
                }.toTagMod
              )))
        case Pending(_) => <.div("Waiting for sources")
        case _ => <.div("No feed sources display")
      }
    }
    .build

  def tableHead: TagOf[TableSection] = {
    val columns = List(
      ("Feed", None),
      ("Flight", None),
      ("Origin", None),
      ("Previous Port", None),
      ("Terminal", None),
      ("Gate / Stand", Option("gate-stand")),
      ("Baggage", None),
      ("Status", Option("status")),
      ("Scheduled", None),
      ("Estimated", None),
      ("Act", None),
      ("Est Chocks", None),
      ("Act Chocks", None),
      ("Total Pax", None),
      ("Trans Pax", None)
    )

    val portColumnThs = columns
      .map {
        case (label, None) => <.th(label)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
      .toTagMod

    <.thead(<.tr(portColumnThs))
  }
}

object FeedSourceRow {

  case class Props(feedSourceArrival: FeedSourceArrival, airportConfig: AirportConfig, paxFeedSourceOrder: List[FeedSource]) extends UseValueEq

  def feedDisplayName(isCiriumAsPortLive: Boolean, feedSource: FeedSource): String =
    if (isCiriumAsPortLive) "Live arrival"
    else feedSource.displayName

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedSourceRow")
    .render_P { props =>
      val isMobile = dom.window.innerWidth < 800
      val feedSource = props.feedSourceArrival.feedSource
      val arrival = props.feedSourceArrival.arrival
      val isCiriumAsPortLive = props.airportConfig.noLivePortFeed && props.airportConfig.aclDisabled
      val paxTotal: String = arrival.bestPaxEstimate(props.paxFeedSourceOrder).passengers.actual.map(_.toString).getOrElse("-")
      val paxTrans: String = arrival.bestPaxEstimate(props.paxFeedSourceOrder).passengers.transit.map(_.toString).getOrElse("-")
      val prevPort: String = arrival.PreviousPort.map(_.iata).getOrElse("n/a")
      val flightFields = List[TagMod](
        <.td(feedDisplayName(isCiriumAsPortLive, feedSource)),
        <.td(arrival.flightCodeString),
        <.td(arrival.Origin.toString),
        <.td(prevPort),
        <.td(arrival.Terminal.toString),
        <.td(s"${arrival.Gate.getOrElse("")}/${arrival.Stand.getOrElse("")}"),
        <.td(s"${arrival.BaggageReclaimId.getOrElse("")}"),
        <.td(if (isMobile) arrival.displayStatusMobile.description else arrival.displayStatus.description),
        <.td(maybeLocalTimeWithPopup(Option(arrival.Scheduled))),
        <.td(maybeLocalTimeWithPopup(arrival.Estimated)),
        <.td(maybeLocalTimeWithPopup(arrival.Actual)),
        <.td(maybeLocalTimeWithPopup(arrival.EstimatedChox)),
        <.td(maybeLocalTimeWithPopup(arrival.ActualChox)),
        <.td(paxTotal),
        <.td(paxTrans),
      )

      <.tr(flightFields.toTagMod)
    }
    .build

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}
