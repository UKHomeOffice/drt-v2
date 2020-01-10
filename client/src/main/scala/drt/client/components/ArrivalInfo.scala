package drt.client.components

import diode.data.{Empty, Pending, Pot, Ready}
import drt.client.components.FlightTableComponents.localDateTimeWithPopup
import drt.shared._
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.TableSection

object ArrivalInfo {

  case class Props(arrivalSources: Pot[List[Option[FeedSourceArrival]]])

  def SourcesTable: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "ArrivalSourcesTable")
    .render_P(props => {
      props.arrivalSources match {
        case Ready(sources) =>
          <.div(
            <.h2(s"Feed sources for arrival"),
            <.table(^.className := "arrivals-table table-striped",
              tableHead(props),
              <.tbody(
                sources.collect { case Some(sourceArrival) =>
                  FeedSourceRow.component(FeedSourceRow.Props(sourceArrival))
                }.toTagMod
              )))
        case Pending(_) => <.div("Waiting for sources")
        case _ => <.div("No feed sources display")
      }
    })
    .build

  def tableHead(props: Props): TagOf[TableSection] = {
    val columns = List(
      ("Feed", None),
      ("Flight", None),
      ("Origin", None),
      ("Gate / Stand", Option("gate-stand")),
      ("Status", Option("status")),
      ("Sch", None),
      ("Est", None),
      ("Act", None),
      ("Est Chox", None),
      ("Act Chox", None),
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

  case class Props(feedSourceArrival: FeedSourceArrival)

  val component = ScalaComponent.builder[Props](displayName = "TableRow")
    .render_P(props => {
      val feedSource = props.feedSourceArrival.feedSource
      val arrival = props.feedSourceArrival.arrival

      val paxTotal: String = arrival.ActPax.map(_.toString).getOrElse("-")
      val paxTrans: String = arrival.TranPax.map(_.toString).getOrElse("-")
      val flightFields = List[TagMod](
        <.td(feedSource.name),
        <.td(arrival.flightCode),
        <.td(arrival.Origin.toString),
        <.td(s"${arrival.Gate.getOrElse("")}/${arrival.Stand.getOrElse("")}"),
        <.td(arrival.Status.description),
        <.td(localDateTimeWithPopup(Option(arrival.Scheduled))),
        <.td(localDateTimeWithPopup(arrival.Estimated)),
        <.td(localDateTimeWithPopup(arrival.Actual)),
        <.td(localDateTimeWithPopup(arrival.EstimatedChox)),
        <.td(localDateTimeWithPopup(arrival.ActualChox)),
        <.td(paxTotal),
        <.td(paxTrans),
      )

      <.tr(flightFields.toTagMod)
    })
    .build

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}
