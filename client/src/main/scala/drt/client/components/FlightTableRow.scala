package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime}
import drt.client.components.FlightComponents.{SplitsGraph, paxFeedSourceClass}
import drt.client.components.styles.ArrivalsPageStylesDefault
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.TimeUtil.millisToMinutes
import drt.shared._
import drt.shared.redlist._
import drt.shared.splits.ApiSplitsToSplitRatio
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf, html_<^}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.{Div, Span}
import scalacss.ScalaCssReact
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.Map

object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = PortCode => VdomNode

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShares: Set[Arrival],
                   idx: Int,
                   timelineComponent: Option[Arrival => html_<^.VdomNode],
                   originMapper: OriginMapperF = portCode => portCode.toString,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean,
                   indirectRedListPax: IndirectRedListPax,
                   directRedListFlight: DirectRedListFlight,
                   airportConfig: AirportConfig,
                   redListUpdates: RedListUpdates,
                   includeIndirectRedListColumn: Boolean,
                  ) extends UseValueEq

  case class RowState(hasChanged: Boolean)

  val component: Component[Props, RowState, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "TableRow")
    .initialState[RowState](RowState(false))
    .render_PS((props, state) => {
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.flightCodeString :: codeShares.map(_.flightCodeString).toList

      val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
      val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

      val queuePax: Map[Queue, Int] = ApiSplitsToSplitRatio
        .paxPerQueueUsingBestSplitsAsRatio(flightWithSplits).getOrElse(Map[Queue, Int]())

      val flightCodeClass = if (props.loggedInUser.hasRole(ArrivalSource))
        "arrivals__table__flight-code arrivals__table__flight-code--clickable"
      else
        "arrivals__table__flight-code"

      def flightCodeElement(flightCodes: String, outgoingDiversion: Boolean, incomingDiversion: Boolean): VdomTagOf[Span] =
        if (props.loggedInUser.hasRole(ArrivalSource)) {
          val diversionClass = (outgoingDiversion, incomingDiversion) match {
            case (_, true) => "arrivals__table__flight-code-incoming-diversion"
            case (true, _) => "arrivals__table__flight-code-outgoing-diversion"
            case _ => ""
          }
          <.span(
            ^.cls := s"arrivals__table__flight-code-value $diversionClass",
            ^.onClick --> Callback(SPACircuit.dispatch {
              props.viewMode match {
                case vm: ViewDay if vm.isHistoric(SDate.now()) && vm.timeMachineDate.isEmpty =>
                  GetArrivalSourcesForPointInTime(SDate(props.viewMode.localDate).addHours(28), props.flightWithSplits.unique)
                case ViewDay(_, Some(tmDate)) =>
                  GetArrivalSourcesForPointInTime(tmDate, props.flightWithSplits.unique)
                case _ =>
                  GetArrivalSources(props.flightWithSplits.unique)
              }
            }),
            flightCodes,
          )
        } else
          <.span(
            ^.cls := "arrivals__table__flight-code-value",
            flightCodes
          )

      val outgoingDiversion = props.directRedListFlight.outgoingDiversion
      val ctaOrRedListMarker = if (flight.Origin.isDomesticOrCta) "*" else ""
      val flightCodes = s"${allCodes.mkString(" - ")}$ctaOrRedListMarker"

      val estimatedContent = (flight.Estimated, flight.predictedTouchdown, props.airportConfig.useTimePredictions) match {
        case (None, Some(value), true) => maybeLocalTimeWithPopup(Option(value), Option("Predicted touchdown based on recent patterns"))
        case _ => maybeLocalTimeWithPopup(flight.Estimated)
      }
      val firstCells = List[TagMod](
        <.td(^.className := flightCodeClass,
          <.div(
            ^.cls := "arrivals__table__flight-code-wrapper",
            flightCodeElement(flightCodes, props.directRedListFlight.outgoingDiversion, props.directRedListFlight.incomingDiversion),
            if (flightWithSplits.hasValidApi)
              <.div(^.className := "arrivals__table__flight-code__info",
                FlightChartComponent(FlightChartComponent.Props(flightWithSplits)))
            else EmptyVdom
          )),
        <.td(props.originMapper(flight.Origin)),
        <.td(TerminalContentComponent.airportWrapper(flight.Origin) { proxy: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            proxy().renderEmpty(<.span()),
            proxy().render(ai => {
              val redListCountry = props.indirectRedListPax.isEnabled && isRedListCountry(ai.country, props.viewMode.dayEnd, props.redListUpdates)
              val style = if (redListCountry) ScalaCssReact.scalacssStyleaToTagMod(ArrivalsPageStylesDefault.redListCountryField) else EmptyVdom
              <.span(style, ai.country)
            })
          )
        }),
        props.indirectRedListPax match {
          case NoIndirectRedListPax => EmptyVdom
          case _ if !props.includeIndirectRedListColumn => EmptyVdom
          case NeboIndirectRedListPax(Some(pax)) => <.td(<.span(^.className := "badge", pax))
          case NeboIndirectRedListPax(None) => <.td(EmptyVdom)
        },
        <.td(gateOrStand(flight, props.airportConfig, props.directRedListFlight.paxDiversion)),
        <.td(^.className := "no-wrap", flight.displayStatus.description),
        <.td(maybeLocalTimeWithPopup(Option(flight.Scheduled))),
        <.td(estimatedContent),
        <.td(maybeLocalTimeWithPopup(flight.Actual)),
      )
      val estCell = List(<.td(maybeLocalTimeWithPopup(flight.EstimatedChox)))
      val lastCells = List[TagMod](
        <.td(maybeLocalTimeWithPopup(flight.ActualChox)),
        <.td(pcpTimeRange(flightWithSplits), ^.className := "arrivals__table__flight-est-pcp"),
        <.td(^.className := s"pcp-pax ${paxFeedSourceClass(flightWithSplits.pcpPaxEstimate)}", FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta))
      )
      val flightFields = if (props.hasEstChox) firstCells ++ estCell ++ lastCells else firstCells ++ lastCells

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      val flightId = flight.uniqueId.toString

      val timeLineTagMod = props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod

      val cancelledClass = if (flight.isCancelled) " arrival-cancelled" else ""
      val noPcpPax = if (flight.Origin.isCta || outgoingDiversion) " arrival-cta" else ""
      val trClassName = s"${offScheduleClass(flight, props.airportConfig.useTimePredictions)} $timeIndicatorClass$cancelledClass$noPcpPax"

      val queueSplits = props.splitsQueueOrder.map { q =>
        val pax = if (!flight.Origin.isDomesticOrCta) queuePax.getOrElse(q, 0).toString else "-"
        <.td(^.className := s"queue-split $paxClass right",
          <.div(pax, ^.className := s"${q.toString.toLowerCase()}-queue-pax"))
      }.toTagMod

      if (props.hasTransfer) {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          hasChangedStyle,
          timeLineTagMod,
          flightFields.toTagMod,
          queueSplits,
          <.td(FlightComponents.paxTransferComponent(flight))
        )
      } else {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          hasChangedStyle,
          timeLineTagMod,
          flightFields.toTagMod,
          queueSplits
        )
      }

    })
    .build

  private def gateOrStand(arrival: Arrival, airportConfig: AirportConfig, paxAreDiverted: Boolean): VdomTagOf[Span] = {
    val gateOrStand = <.span(^.className := "no-wrap", s"${arrival.Gate.getOrElse("")} / ${arrival.Stand.getOrElse("")}")
    arrival.walkTime(airportConfig.firstPaxOffMillis, airportConfig.useTimePredictions).map { wt =>
      val description = (paxAreDiverted, arrival.Stand.isDefined, arrival.Gate.isDefined) match {
        case (true, _, _) => "walk time including transfer bus"
        case (false, true, _) => "walk time from stand"
        case (false, false, true) => "walk time from gate"
        case _ => "default walk time"
      }
      val walkTimeString = MinuteAsAdjective(millisToMinutes(wt)).display + " " + description
      <.span(^.className := "no-wrap", Tippy.interactive(<.span(walkTimeString), gateOrStand))
    }.getOrElse(gateOrStand)
  }

  def offScheduleClass(arrival: Arrival, considerPredictions: Boolean): String = {
    val eta = arrival.bestArrivalTime(considerPredictions)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "danger"
    else ""
    offScheduleClass
  }

  def isRedListCountry(country: String, date: SDateLike, redListUpdates: RedListUpdates): Boolean =
    redListUpdates.countryCodesByName(date.millisSinceEpoch).keys.exists(_.toLowerCase == country.toLowerCase)

  def apply(props: Props): Unmounted[Props, RowState, Unit] = component(props)

}
