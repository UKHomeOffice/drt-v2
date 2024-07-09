package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime}
import drt.client.components.FlightComponents.{SplitsGraph, paxFeedSourceClass}
import drt.client.components.styles.{ArrivalsPageStylesDefault, DrtTheme}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, PaxAgeRange, WalkTimes}
import drt.shared.redlist._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf, html_<^}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.{Div, Span}
import scalacss.ScalaCssReact
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.PaxTypes.{Transit, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.splits.ApiSplitsToSplitRatio
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.SDateLike

import scala.scalajs.js

object FlightTableRow {

  import FlightTableComponents._

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShareFlightCodes: Seq[String],
                   idx: Int,
                   originMapper: PortCode => VdomNode = portCode => portCode.toString,
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
                   walkTimes: WalkTimes,
                   flaggedNationalities: Set[Country],
                   flaggedAgeGroups: Set[PaxAgeRange],
                   showTransitPaxNumber: Boolean,
                   showNumberOfVisaNationals: Boolean,
                   showHighlightedRows: Boolean,
                   showRequireAllSelected: Boolean,
                   manifestSummary: Option[FlightManifestSummary],
                   paxFeedSourceOrder: List[FeedSource],
                  ) extends UseValueEq

  implicit val propsReuse: Reusability[Props] = Reusability {
    (a, b) =>
      a.flightWithSplits.lastUpdated == b.flightWithSplits.lastUpdated &&
        a.manifestSummary == b.manifestSummary &&
        a.flaggedNationalities == b.flaggedNationalities
  }

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .render_P { props =>
      val isMobile = dom.window.innerWidth < 800
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.flightCodeString :: props.codeShareFlightCodes.toList

      val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

      val queuePax: Map[Queue, Int] = ApiSplitsToSplitRatio
        .paxPerQueueUsingBestSplitsAsRatio(flightWithSplits, props.paxFeedSourceOrder).getOrElse(Map[Queue, Int]())

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

      val arrivalTimes: Seq[(String, Long)] = Seq(
        "Predicted" -> flight.predictedTouchdown,
        "Estimated" -> flight.Estimated,
        "Touchdown" -> flight.Actual,
        "Estimated Chox" -> flight.EstimatedChox,
        "Actual Chox" -> flight.ActualChox,
      ).collect {
        case (name, Some(time)) => name -> time
      }

      val timesPopUp = arrivalTimes.map(t => <.div(
        <.span(^.display := "inline-block", ^.width := "120px", t._1), <.span(SDate(t._2).toLocalDateTimeString.takeRight(5))
      )).toTagMod

      val bestExpectedTime = arrivalTimes.reverse.headOption.map(_._2)

      val expectedContent = maybeLocalTimeWithPopup(bestExpectedTime, Option(timesPopUp), None)

      val charts = (flightWithSplits.hasApi, props.manifestSummary) match {
        case (true, Some(manifestSummary)) =>
          val maybeLivePcpPax = flightWithSplits.apiFlight.bestPcpPaxEstimate(Seq(LiveFeedSource))
          val maybePaxDiffAndPct = maybeLivePcpPax.map { pcpPax =>
            val diff = pcpPax - manifestSummary.passengerCount
            (diff, diff.toDouble / pcpPax)
          }
          if (maybePaxDiffAndPct.isEmpty || maybePaxDiffAndPct.exists(_._2 <= 1.05))
            <.div(^.className := "arrivals__table__flight-code__info",
              FlightChartComponent(FlightChartComponent.Props(manifestSummary, maybePaxDiffAndPct)))
          else EmptyVdom
        case _ => EmptyVdom
      }

      val firstCells = List[TagMod](
        <.td(^.className := flightCodeClass,
          <.div(
            ^.cls := "arrivals__table__flight-code-wrapper",
            flightCodeElement(flightCodes, props.directRedListFlight.outgoingDiversion, props.directRedListFlight.incomingDiversion),
            charts
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
        if (props.flaggedNationalities.nonEmpty || props.flaggedAgeGroups.nonEmpty || props.showTransitPaxNumber || props.showNumberOfVisaNationals)
          <.td(^.className := "arrivals__table__flags-column", highlightedChips(props.showTransitPaxNumber,
            props.showNumberOfVisaNationals,
            props.showRequireAllSelected,
            props.flaggedAgeGroups,
            props.flaggedNationalities,
            props.manifestSummary)) else EmptyVdom,
        <.td(gateOrStand(flight, props.airportConfig.defaultWalkTimeMillis(flight.Terminal), props.directRedListFlight.paxDiversion, props.walkTimes)),
        <.td(^.className := "no-wrap", if (isMobile) flight.displayStatusMobile.description else flight.displayStatus.description),
        <.td(maybeLocalTimeWithPopup(Option(flight.Scheduled))),
        <.td(expectedContent),
      )
      val lastCells = List[TagMod](
        <.td(pcpTimeRange(flightWithSplits, props.airportConfig.firstPaxOffMillis, props.walkTimes, props.paxFeedSourceOrder), ^.className := "arrivals__table__flight-est-pcp"),
        <.td(^.className := s"pcp-pax ${paxFeedSourceClass(flightWithSplits.apiFlight.bestPaxEstimate(props.paxFeedSourceOrder), flight.Origin.isDomesticOrCta)}", FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta, props.paxFeedSourceOrder)),
      )

      val flightFields = firstCells ++ lastCells

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      val flightId = flight.uniqueId.toString

      val cancelledClass = if (flight.isCancelled) " arrival-cancelled" else ""
      val noPcpPax = if (flight.Origin.isCta || outgoingDiversion) " arrival-cta" else ""
      val trClassName = s"${offScheduleClass(flight)} $timeIndicatorClass$cancelledClass$noPcpPax"

      val queueSplits = props.splitsQueueOrder.map { q =>
        val pax = if (!flight.Origin.isDomesticOrCta) queuePax.getOrElse(q, 0).toString else "-"
        <.td(^.className := s"queue-split $paxClass right",
          <.div(pax, ^.className := s"${q.toString.toLowerCase()}-queue-pax"))
      }.toTagMod

      if (props.hasTransfer) {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          flightFields.toTagMod,
          queueSplits,
          <.td(FlightComponents.paxTransferComponent(flight, props.paxFeedSourceOrder))
        )
      } else {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          flightFields.toTagMod,
          queueSplits
        )
      }
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def highlightedChips(showTransitPaxNumber: Boolean,
                               showNumberOfVisaNationals: Boolean,
                               showRequireAllSelected: Boolean,
                               flaggedAgeGroups: Set[PaxAgeRange],
                               flaggedNationalities: Set[Country],
                               manifestSummary: Option[FlightManifestSummary]): html_<^.VdomNode = {
    <.div(
      manifestSummary.map { summary =>
        def generateChip(condition: Boolean, pax: Int, label: String): Option[VdomElement] = {
          if (condition && pax > 0) Option(FlightHighlightChip(s"($pax) $label"))
          else None
        }

        val flaggedNationalitiesChips = flaggedNationalities.flatMap { country =>
          val pax = summary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, country.threeLetterCode)
        }

        val flaggedAgeGroupsChips = flaggedAgeGroups.flatMap { ageRanges =>
          val pax = summary.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, ageRanges.title)
        }

        val visaNationalsChip = generateChip(showNumberOfVisaNationals, summary.paxTypes.getOrElse(VisaNational, 0), "Visa Nationals")
        val transitChip = generateChip(showTransitPaxNumber, summary.paxTypes.getOrElse(Transit, 0), "Transit")

        val chips: Set[VdomElement] = flaggedNationalitiesChips ++ flaggedAgeGroupsChips ++ visaNationalsChip ++ transitChip
        if (showRequireAllSelected) {
          (flaggedNationalities.nonEmpty, flaggedAgeGroups.nonEmpty, showNumberOfVisaNationals, showTransitPaxNumber) match {
            case (true, true, true, true) if (flaggedNationalitiesChips.nonEmpty && flaggedAgeGroupsChips.nonEmpty && visaNationalsChip.nonEmpty && transitChip.nonEmpty) =>
              chips.toTagMod
            case (true, true, true, false) if (flaggedNationalitiesChips.nonEmpty && flaggedAgeGroupsChips.nonEmpty && visaNationalsChip.nonEmpty) =>
              (flaggedNationalitiesChips ++ flaggedAgeGroupsChips ++ visaNationalsChip).toTagMod

            case (true, true, false, false) if (flaggedNationalitiesChips.nonEmpty && flaggedAgeGroupsChips.nonEmpty) =>
              (flaggedNationalitiesChips ++ flaggedAgeGroupsChips).toTagMod

            case (true, true, false, true) if (flaggedNationalitiesChips.nonEmpty && flaggedAgeGroupsChips.nonEmpty && transitChip.nonEmpty) =>
              (flaggedNationalitiesChips ++ flaggedAgeGroupsChips ++ transitChip).toTagMod

            case (true, false, true, true) if (flaggedNationalitiesChips.nonEmpty && visaNationalsChip.nonEmpty && transitChip.nonEmpty) =>
              (flaggedNationalitiesChips ++ visaNationalsChip ++ transitChip).toTagMod

            case (true, false, true, false) if (flaggedNationalitiesChips.nonEmpty && visaNationalsChip.nonEmpty) =>
              (flaggedNationalitiesChips ++ visaNationalsChip).toTagMod

            case (true, false, false, true) if (flaggedNationalitiesChips.nonEmpty && transitChip.nonEmpty) =>
              (flaggedNationalitiesChips ++ transitChip).toTagMod

            case (true, false, false, false) if (flaggedNationalitiesChips.nonEmpty) =>
              flaggedNationalitiesChips.toTagMod

            case (false, true, true, true) if (flaggedAgeGroupsChips.nonEmpty && visaNationalsChip.nonEmpty && transitChip.nonEmpty) =>
              (flaggedAgeGroupsChips ++ visaNationalsChip ++ transitChip).toTagMod

            case (false, true, true, false) if (flaggedAgeGroupsChips.nonEmpty && visaNationalsChip.nonEmpty) =>
              (flaggedAgeGroupsChips ++ visaNationalsChip).toTagMod

            case (false, true, false, false) if (flaggedAgeGroupsChips.nonEmpty) =>
              flaggedAgeGroupsChips.toTagMod

            case (false, false, true, true) if (visaNationalsChip.nonEmpty && transitChip.nonEmpty) =>
              (visaNationalsChip ++ transitChip).toTagMod

            case (false, false, true, false) if (visaNationalsChip.nonEmpty) =>
              visaNationalsChip.toTagMod

            case (false, false, false, true) if (transitChip.nonEmpty) =>
              transitChip.toTagMod

            case _ => EmptyVdom
          }
        } else {
          if (chips.nonEmpty) {
            <.div(
              ^.style := js.Dictionary("display" -> "flex", "flexWrap" -> "wrap", "gap" -> "8px"),
              chips.toTagMod
            )
          } else EmptyVdom
        }
      }.getOrElse(EmptyVdom))
  }

  private def gateOrStand(arrival: Arrival, terminalWalkTime: Long, paxAreDiverted: Boolean, walkTimes: WalkTimes): VdomTagOf[Span] = {
    val gateOrStand = <.span(^.key := "gate-or-stand", ^.className := "no-wrap", s"${arrival.Gate.getOrElse("")} / ${arrival.Stand.getOrElse("")}")
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
    <.span(^.className := "no-wrap", Tippy.interactive(<.span(^.key := "walk-time", walkTimeString), gateOrStand))
  }

  def offScheduleClass(arrival: Arrival): String = {
    val eta = arrival.bestArrivalTime(considerPredictions = true)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "arrivals-table__danger"
    else ""
    offScheduleClass
  }

  def isRedListCountry(country: String, date: SDateLike, redListUpdates: RedListUpdates): Boolean =
    redListUpdates.countryCodesByName(date.millisSinceEpoch).keys.exists(_.toLowerCase == country.toLowerCase)

}
