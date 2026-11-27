package drt.client.components

import diode.UseValueEq
import diode.data.{ Pending, Pot, Ready }
import drt.client.components.FlightTableComponents.maybeLocalTimeWithPopup
import drt.shared._
import japgolly.scalajs.react.component.Scala.{ Component, Unmounted }
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{ TagMod, TagOf }
import japgolly.scalajs.react.{ CtorType, _ }
import org.scalajs.dom
import org.scalajs.dom.html.TableSection
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.{ AirportConfig, ApiFeedSource, FeedSource }
import io.kinoplan.scalajs.react.material.ui.core.MuiButton

object ArrivalInfo {

  case class Props(
      arrivalSources: Pot[List[Option[FeedSourceArrival]]],
      airportConfig: AirportConfig,
      paxFeedSourceOrder: List[FeedSource],
      onClose: Callback
  ) extends UseValueEq

  private val modalSelector = ".arrivals-sources-modal"
  private val focusableSelector = "a[href], button:not([disabled]), input, select, textarea, [tabindex='0']"

  def shouldWrapFocus(modal: dom.Element, activeElement: dom.Element, shiftKey: Boolean): Option[dom.Element] = {
    val focusableElements = modal.querySelectorAll(focusableSelector)
    if (focusableElements.length > 0) {
      val firstElement = focusableElements.item(0)
      val lastElement = focusableElements.item(focusableElements.length - 1)
      val wrapToFirst = !shiftKey && activeElement == lastElement
      val wrapToLast = shiftKey && activeElement == firstElement

      if (wrapToFirst) Some(firstElement)
      else if (wrapToLast) Some(lastElement)
      else None
    } else None
  }

  private def trapFocus(e: ReactKeyboardEventFromHtml): Callback =
    if (e.key == "Tab") {
      Callback {
        Option(dom.document.querySelector(modalSelector)).foreach { modal =>
          shouldWrapFocus(modal, dom.document.activeElement, e.shiftKey) match {
            case Some(elementToFocus) =>
              elementToFocus match {
                case el: dom.HTMLElement =>
                  e.preventDefault()
                  el.focus()
                case _ =>
              }
            case None =>
          }
        }
      }
    } else Callback.empty

  /** One row per configured feed, in display order, so feeds that sent nothing still appear (as dashes). */
  def rowsFor(
               sources: Seq[Option[FeedSourceArrival]],
               configuredFeedSources: Seq[FeedSource]
             ): Seq[(FeedSource, Option[Arrival])] = {
    val arrivalsBySource = sources.flatten.map(fsa => (fsa.feedSource, fsa.arrival)).toMap
    FeedSourceArrival.orderedSourcesFor(configuredFeedSources).map(source => (source, arrivalsBySource.get(source)))
  }

  private def tableContent(props: Props): VdomNode = props.arrivalSources match {
    case Ready(sources) =>
      <.table(
        ^.className := "arrivals-table table-striped",
        tableHead,
        <.tbody(
          rowsFor(sources, props.airportConfig.feedSources).map { case (feedSource, maybeArrival) =>
            FeedSourceRow.component(FeedSourceRow.Props(
              feedSource,
              maybeArrival,
              props.airportConfig,
              props.paxFeedSourceOrder
            ))
          }.toTagMod
        )
      )
    case Pending(_) => <.div("Waiting for sources")
    case _          => <.div("No feed sources display")
  }

  val SourcesTable: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalSourcesTable")
    .render_P { props =>
      <.div(
        ^.className := "arrivals-sources-modal",
        ^.onKeyDown ==> trapFocus,
        <.div(
          ^.className := "arrivals-sources-header",
          <.h2(
            ^.className := "arrivals-sources-title",
            ^.tabIndex := 0,
            "Feed sources for arrival"
          ),
          MuiButton(variant = "text", size = "small")(
            ^.className := "arrivals-sources-close",
            ^.aria.label := "Close feed sources for arrival",
            ^.onClick --> props.onClose,
            "Close"
          )
        ),
        <.div(
          ^.className := "arrivals-sources-table-focus",
          ^.tabIndex := 0,
          ^.aria.label := "Feed sources table",
          tableContent(props)
        )
      )
    }
    .componentDidMount(_ =>
      Callback {
        dom.document.querySelector(".arrivals-sources-title") match {
          case el: dom.HTMLElement =>
            el.focus()
          case _ =>
        }
      }
    )
    .build

  val columns: List[(String, Option[String])] = List(
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

  def tableHead: TagOf[TableSection] = {
    val portColumnThs = columns
      .map {
        case (label, None)            => <.th(label)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
      .toTagMod

    <.thead(<.tr(portColumnThs))
  }
}

object FeedSourceRow {
  val noData: String = "-"

  case class Props(
                    feedSource: FeedSource,
                    maybeArrival: Option[Arrival],
                    airportConfig: AirportConfig,
                    paxFeedSourceOrder: List[FeedSource]
                  ) extends UseValueEq

  private def nonBlank(maybeValue: Option[String]): Option[String] = maybeValue.filter(_.trim.nonEmpty)

  def orDash(maybeValue: Option[String]): String = nonBlank(maybeValue).getOrElse(noData)

  def gateStand(arrival: Arrival): String =
    (nonBlank(arrival.Gate), nonBlank(arrival.Stand)) match {
      case (None, None)  => noData
      case (gate, stand) => s"${orDash(gate)} / ${orDash(stand)}"
    }

  private def timeOrDash(maybeMillis: Option[Long]): TagMod =
    maybeMillis.fold[TagMod](noData)(millis => maybeLocalTimeWithPopup(Option(millis)))

  private def dataCells(feedSource: FeedSource, arrival: Arrival, paxFeedSourceOrder: List[FeedSource]): List[TagMod] = {
    val isMobile = dom.window.innerWidth < 800
    // API data doesn't supply terminal or status, so those come from other feeds and shouldn't be shown against API
    val isApi = feedSource == ApiFeedSource
    val pax = arrival.bestPaxEstimate(paxFeedSourceOrder).passengers
    val status = if (isMobile) arrival.displayStatusMobile.description else arrival.displayStatus.description
    List[TagMod](
      <.td(arrival.flightCodeString),
      <.td(arrival.Origin.toString),
      <.td(orDash(arrival.PreviousPort.map(_.iata))),
      <.td(if (isApi) noData else arrival.Terminal.toString),
      <.td(gateStand(arrival)),
      <.td(orDash(arrival.BaggageReclaimId)),
      <.td(if (isApi) noData else status),
      <.td(maybeLocalTimeWithPopup(Option(arrival.Scheduled))),
      <.td(timeOrDash(arrival.Estimated)),
      <.td(timeOrDash(arrival.Actual)),
      <.td(timeOrDash(arrival.EstimatedChox)),
      <.td(timeOrDash(arrival.ActualChox)),
      <.td(orDash(pax.actual.map(_.toString))),
      <.td(orDash(pax.transit.map(_.toString)))
    )
  }

  private val dataColumnCount: Int = ArrivalInfo.columns.size - 1

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FeedSourceRow")
    .render_P { props =>
      val hasOperatorLiveFeed = !props.airportConfig.noLivePortFeed
      val cells = props.maybeArrival match {
        case Some(arrival) => dataCells(props.feedSource, arrival, props.paxFeedSourceOrder)
        case None          => List.fill[TagMod](dataColumnCount)(<.td(noData))
      }

      <.tr((<.td(FeedSourceDisplay.displayName(props.feedSource, hasOperatorLiveFeed)) :: cells).toTagMod)
    }
    .build

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}
