package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime}
import drt.client.components.FlightComponents.{SplitsDataQuality, paxFeedSourceClass}
import drt.client.components.styles.{ArrivalsPageStylesDefault, DrtReactTheme}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.MinuteAsAdjective
import drt.shared.api.{FlightManifestSummary, PaxAgeRange, WalkTimes}
import drt.shared.redlist._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, html_<^}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.{Span, TableRow}
import scalacss.ScalaCssReact
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.splits.ApiSplitsToSplitRatio
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.SDateLike

object FlightTableRow {

  import FlightTableComponents._

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShareFlightCodes: Seq[String],
                   originMapper: (PortCode, Option[PortCode], html_<^.TagMod) => VdomNode,
                   splitsQueueOrder: Seq[Queue],
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   hasTransfer: Boolean,
                   indirectRedListPax: IndirectRedListPax,
                   directRedListFlight: DirectRedListFlight,
                   airportConfig: AirportConfig,
                   redListUpdates: RedListUpdates,
                   includeIndirectRedListColumn: Boolean,
                   walkTimes: WalkTimes,
                   flaggedNationalities: Set[drt.shared.Country],
                   flaggedAgeGroups: Set[PaxAgeRange],
                   showNumberOfVisaNationals: Boolean,
                   showHighlightedRows: Boolean,
                   showRequireAllSelected: Boolean,
                   maybeManifestSummary: Option[FlightManifestSummary],
                   paxFeedSourceOrder: List[FeedSource],
                   showHighLighted: Boolean,
                   hidePaxDataSourceDescription: Boolean
                  ) extends UseValueEq

  implicit val propsReuse: Reusability[Props] = Reusability {
    (a, b) =>
      a.flightWithSplits.lastUpdated == b.flightWithSplits.lastUpdated &&
        a.maybeManifestSummary == b.maybeManifestSummary &&
        a.flaggedNationalities == b.flaggedNationalities &&
        a.flaggedAgeGroups == b.flaggedAgeGroups
  }

  class Backend {
    def render(props: Props): VdomTagOf[TableRow] = {
      val isMobile = dom.window.innerWidth < 800
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.flightCodeString :: props.codeShareFlightCodes.toList

      val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

      val queuePax: Map[Queue, Int] = ApiSplitsToSplitRatio
        .paxPerQueueUsingBestSplitsAsRatio(flightWithSplits, props.paxFeedSourceOrder).getOrElse(Map[Queue, Int]())

      val highlighterIsActive = props.flaggedNationalities.nonEmpty || props.flaggedAgeGroups.nonEmpty || props.showNumberOfVisaNationals

      val highlightPaxExists: Boolean = FlightHighlighter.highlightedFlight(props.maybeManifestSummary,
        props.flaggedNationalities,
        props.flaggedAgeGroups,
        props.showNumberOfVisaNationals,
        props.showHighlightedRows,
        props.showRequireAllSelected).contains(true)

      val highlightedComponent = if (props.showHighLighted && highlighterIsActive) {
        val chip = FlightHighlighter.highlightedColumnData(
          props.showNumberOfVisaNationals,
          props.showRequireAllSelected,
          props.flaggedAgeGroups,
          props.flaggedNationalities,
          props.maybeManifestSummary)
        if (chip != EmptyVdom) Some(chip) else None
      } else None

      val flightCodeClass = if (props.loggedInUser.hasRole(ArrivalSource))
        if (props.showHighLighted && highlighterIsActive)
          "arrivals__table__flight-code arrivals__table__flight-code--clickable"
        else
          "arrivals__table__flight-code--clickable"
      else if (props.showHighLighted && highlighterIsActive) ""
      else "arrivals__table__flight-code"

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
            if (highlightPaxExists)
              FlightHighlightChip(flightCodes)
            else if (highlighterIsActive)
              <.span(^.cls := "arrival__non__highter__row", flightCodes)
            else flightCodes)

        } else <.span(^.cls := "arrivals__table__flight-code-value", flightCodes)


      val outgoingDiversion = props.directRedListFlight.outgoingDiversion
      val ctaOrRedListMarker = if (flight.Origin.isDomesticOrCta) "*" else ""
      val flightCodes = s"${allCodes.mkString(" - ")}$ctaOrRedListMarker"

      val arrivalTimes: Seq[(String, Long)] = Seq(
        "Predicted" -> flight.predictedTouchdown,
        "Estimated" -> flight.Estimated,
        "Touchdown" -> flight.Actual,
        "Estimated Chocks" -> flight.EstimatedChox,
        "Actual Chocks" -> flight.ActualChox,
      ).collect {
        case (name, Some(time)) => name -> time
      }

      val timesPopUp = arrivalTimes.map { case (label, time) =>
        <.div(^.key := label.toLowerCase.replaceAll(" ", "-"),
          <.span(^.display := "inline-block", ^.width := "120px", label), <.span(SDate(time).toLocalDateTimeString.takeRight(5))
        )
      }.toTagMod

      val bestExpectedTime = arrivalTimes.reverse.headOption.map(_._2)

      val expectedContent = maybeLocalTimeWithPopup(bestExpectedTime, Option(timesPopUp), None)

      val charts = (flightWithSplits.hasApi, props.maybeManifestSummary) match {
        case (true, Some(manifestSummary)) =>
          val maybeLivePcpPax = flightWithSplits.apiFlight.bestPcpPaxEstimate(Seq(LiveFeedSource))
          val maybePaxDiffAndPct = maybeLivePcpPax.map { pcpPax =>
            val diff = pcpPax - manifestSummary.passengerCount
            (diff, diff.toDouble / pcpPax)
          }
          if (maybePaxDiffAndPct.isEmpty || maybePaxDiffAndPct.exists(_._2 <= 1.05)) {
            val cls = if (props.showHighLighted) "arrivals__table__flight-code__info-highlighted" else "arrivals__table__flight-code__info"
            <.div(^.className := cls,
              FlightChartComponent(FlightChartComponent.Props(manifestSummary, maybePaxDiffAndPct)))
          } else EmptyVdom
        case _ => EmptyVdom
      }

      val highlighterClass = s"arrivals__table__flight-code__highlighter-${if (highlighterIsActive) "on" else "off"}"
      val isHighlightedClass = if (highlighterIsActive) "arrivals__table__flight-code-wrapper__highlighted" else ""

      val maybePreviousPort = None //flight.PreviousPort.filter(_ != flight.Origin)

      val firstCells = List[TagMod](
        <.td(^.className := flightCodeClass,
          <.div(^.cls := s"$highlighterClass $isHighlightedClass arrivals__table__flight-code-wrapper",
            flightCodeElement(flightCodes, outgoingDiversion, props.directRedListFlight.incomingDiversion), charts)
        ),
        highlightedComponent.map(<.td(^.className := "arrivals__table__flags-column", _))
          .getOrElse {
            if (highlighterIsActive) <.td(^.className := "arrivals__table__flags-column", "") else EmptyVdom
          },
        <.td(TerminalContentComponent.airportWrapper(flight.Origin) { airportInfoPot: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            airportInfoPot().renderEmpty(props.originMapper(flight.Origin, maybePreviousPort, EmptyVdom)),
            airportInfoPot().render { ai =>
              val redListCountry = props.indirectRedListPax.isEnabled && isRedListCountry(ai.country, props.viewMode.dayEnd, props.redListUpdates)
              val style: html_<^.TagMod = if (redListCountry) ScalaCssReact.scalacssStyleaToTagMod(ArrivalsPageStylesDefault.redListCountryField) else EmptyVdom
              props.originMapper(flight.Origin, maybePreviousPort, style)
            }
          )
        }),
        props.indirectRedListPax match {
          case NoIndirectRedListPax => EmptyVdom
          case _ if !props.includeIndirectRedListColumn => EmptyVdom
          case NeboIndirectRedListPax(Some(pax)) => <.td(<.span(^.className := "badge", pax))
          case NeboIndirectRedListPax(None) => <.td(EmptyVdom)
        },
        <.td(gateOrStand(flight, props.airportConfig.defaultWalkTimeMillis(flight.Terminal), props.directRedListFlight.paxDiversion, props.walkTimes)),
        <.td(^.className := "no-wrap", if (isMobile) flight.displayStatusMobile.description else flight.displayStatus.description),
        <.td(maybeLocalTimeWithPopup(Option(flight.Scheduled))),
        <.td(expectedContent),
      )
      val pcpPaxDataQuality = paxFeedSourceClass(flightWithSplits.apiFlight.bestPaxEstimate(props.paxFeedSourceOrder), flight.Origin.isDomesticOrCta)
      val lastCells = List[TagMod](
        <.td(
          pcpTimeRange(flightWithSplits, props.airportConfig.firstPaxOffMillis, props.walkTimes, props.paxFeedSourceOrder),
          ^.className := "arrivals__table__flight-est-pcp"
        ),
        <.td(
          pcpPaxDataQuality.map(dq =>
            if (props.hidePaxDataSourceDescription) {
              <.div(^.className := s"pcp-icon-data-quality pax-rag-${dq.`type`}",
                PaxDatasourceComponent(IPaxDatasource(dq.text)),
                FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta, props.paxFeedSourceOrder))
            } else {
              <.div(^.className := "text-data-quality",
                FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta, props.paxFeedSourceOrder),
                DataQualityIndicator(dq, flight.Terminal, "pax-rag", icon = false))
            }
          ), ^.className := s"pcp-pax"),
      )

      val flightFields = firstCells ++ lastCells

      val flightId = flight.uniqueId.toString

      val splitsDataQuality = FlightComponents.splitsDataQuality(flightWithSplits)

      val splitsOrder = props.splitsQueueOrder.map { q =>
        val pax = if (!flight.Origin.isDomesticOrCta) queuePax.getOrElse(q, 0).toString else "-"
        <.div(
          <.div(^.className := "arrivals_table__Splits__split-number", pax),
          ^.className := s"${q.toString.toLowerCase()}-queue-pax arrivals_table__splits__queue-pax")
      }.toTagMod

      def splits(dq: SplitsDataQuality) = ThemeProvider(DrtReactTheme)(if (props.hidePaxDataSourceDescription)
        <.div(
          <.span(^.className := "flex-uniform-size",
            <.span(^.className := "icon-data-quality", PaxDatasourceComponent(IPaxDatasource(dq.text))),
            splitsOrder)
        ) else
        <.div(
          <.span(^.className := "flex-uniform-size", splitsOrder),
          DataQualityIndicator(dq, flight.Terminal, "splits-rag", icon = false)
        ))

      val queueSplits = <.td(
        splitsDataQuality.map(dq => splits(dq))
      )

      val cancelledClass = if (flight.isCancelled) " arrival-cancelled" else ""
      val noPcpPax = if (flight.Origin.isCta || outgoingDiversion) " arrival-cta" else ""
      val trClassName = s"${offScheduleClass(flight)} $timeIndicatorClass$cancelledClass$noPcpPax"

      <.tr(
        ^.key := flightId,
        ^.className := trClassName,
        flightFields.toTagMod,
        queueSplits,
        if (props.hasTransfer)
          <.td(^.className := "arrivals__table__flight_transfer-pax", FlightComponents.paxTransferComponent(flight, props.paxFeedSourceOrder))
        else EmptyVdom
      )
    }
  }

  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def gateOrStand(arrival: Arrival, terminalWalkTime: Long, paxAreDiverted: Boolean, walkTimes: WalkTimes): VdomTagOf[Span] = {
    val content = (arrival.Gate, arrival.Stand) match {
      case (Some(gate), Some(stand)) => <.span(s"Gate: $gate", <.br(), s"Stand: $stand")
      case (Some(gate), _) => <.span(s"Gate: $gate")
      case (_, Some(stand)) => <.span(s"Stand: $stand")
      case _ => <.span("Not available")
    }
    val gateOrStand = <.span(^.key := "gate-or-stand", ^.className := "no-wrap underline", content)
    val maybeActualWalkTime = walkTimes.maybeWalkTimeMinutes(arrival.Gate, arrival.Stand, arrival.Terminal)

    val description = (paxAreDiverted, maybeActualWalkTime.isDefined) match {
      case (true, _) => "walk time including transfer bus"
      case (_, true) =>
        val gateOrStand = if (arrival.Stand.isDefined) "stand" else "gate"
        s"walk time from $gateOrStand"
      case _ => "default walk time"
    }
    val walkTime = maybeActualWalkTime.getOrElse((terminalWalkTime / oneMinuteMillis).toInt)
    val walkTimeString = MinuteAsAdjective(walkTime).display + " " + description
    <.span(^.className := "no-wrap", ^.key := "walk-time", Tippy.interactive(<.span(walkTimeString), gateOrStand))
  }

  private def offScheduleClass(arrival: Arrival): String = {
    val eta = arrival.bestArrivalTime(considerPredictions = true)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "arrivals-table__danger"
    else ""
    offScheduleClass
  }

  private def isRedListCountry(country: String, date: SDateLike, redListUpdates: RedListUpdates): Boolean =
    redListUpdates.countryCodesByName(date.millisSinceEpoch).keys.exists(_.toLowerCase == country.toLowerCase)

}
