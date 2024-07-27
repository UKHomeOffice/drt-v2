package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime}
import drt.client.components.FlightComponents.{SplitsGraph, paxFeedSourceClass}
import drt.client.components.styles.ArrivalsPageStylesDefault
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
import uk.gov.homeoffice.drt.ports.PaxTypes.VisaNational
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
                   originMapper: (PortCode, html_<^.TagMod) => VdomNode, // = portCode => portCode.toString,
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
                   showNumberOfVisaNationals: Boolean,
                   showHighlightedRows: Boolean,
                   showRequireAllSelected: Boolean,
                   manifestSummary: Option[FlightManifestSummary],
                   paxFeedSourceOrder: List[FeedSource],
                   showHightLighted: Boolean,
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

      val highlighterIsActive = props.flaggedNationalities.nonEmpty ||
        props.flaggedAgeGroups.nonEmpty || props.showNumberOfVisaNationals

      val flightCodeClass = if (props.loggedInUser.hasRole(ArrivalSource))
        if (props.showHightLighted && highlighterIsActive)
          "arrivals__table__flight-code arrivals__table__flight-code--clickable"
        else
          "arrivals__table__flight-code--clickable"
      else if (props.showHightLighted && highlighterIsActive) ""
      else "arrivals__table__flight-code"

      val highlightedComponent = if (highlighterIsActive) {
        val chip = highlightedChips(
          props.showNumberOfVisaNationals,
          props.showRequireAllSelected,
          props.flaggedAgeGroups,
          props.flaggedNationalities,
          props.manifestSummary)
        if (chip != EmptyVdom) Some(chip) else None
      } else None

      def flightCodeElement(flightCodes: String, outgoingDiversion: Boolean, incomingDiversion: Boolean, showHighlighter: Boolean): VdomTagOf[Span] =
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
            if (showHighlighter) FlightHighlightChip(flightCodes) else if (!props.showHightLighted && highlighterIsActive) <.span(^.cls := "arrival__non__highter__row", flightCodes) else flightCodes
          )
        } else if (showHighlighter) <.span(^.cls := "arrivals__table__flight-code-value", FlightHighlightChip(flightCodes))
        else <.span(^.cls := "arrivals__table__flight-code-value", flightCodes)


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

      val timesPopUp = arrivalTimes.map { case (label, time) =>
        <.div(^.key := label.toLowerCase.replaceAll(" ", "-"),
          <.span(^.display := "inline-block", ^.width := "120px", label), <.span(SDate(time).toLocalDateTimeString.takeRight(5))
        )
      }.toTagMod

      val bestExpectedTime = arrivalTimes.reverse.headOption.map(_._2)

      val expectedContent = maybeLocalTimeWithPopup(bestExpectedTime, Option(timesPopUp), None)

      val charts = (flightWithSplits.hasApi, props.manifestSummary) match {
        case (true, Some(manifestSummary)) =>
          val maybeLivePcpPax = flightWithSplits.apiFlight.bestPcpPaxEstimate(Seq(LiveFeedSource))
          val maybePaxDiffAndPct = maybeLivePcpPax.map { pcpPax =>
            val diff = pcpPax - manifestSummary.passengerCount
            (diff, diff.toDouble / pcpPax)
          }
          if (maybePaxDiffAndPct.isEmpty || maybePaxDiffAndPct.exists(_._2 <= 1.05)) {
            val cls = if (props.showHightLighted) "arrivals__table__flight-code__info-highlighted" else "arrivals__table__flight-code__info"
            <.div(^.className := cls,
              FlightChartComponent(FlightChartComponent.Props(manifestSummary, maybePaxDiffAndPct)))
          } else EmptyVdom
        case _ => EmptyVdom
      }

      val highlighterClass = s"arrivals__table__flight-code__highlighter-${if (highlighterIsActive) "on" else "off"}"
      val isHighlightedClass = if (props.showHightLighted) "arrivals__table__flight-code-wrapper__highlighted" else ""

//      val wrapperClass = if (props.showHightLighted) "arrivals__table__flight-code-wrapper__highlighted"
//      else if (!props.showHightLighted && highlighterIsActive) "arrivals__table__flight-code-wrapper__non-highlighted"
//      else "arrivals__table__flight-code-wrapper__highlighter-inactive"
      val firstCells = List[TagMod](
        <.td(^.className := flightCodeClass,
          <.div(^.cls := s"$highlighterClass $isHighlightedClass arrivals__table__flight-code-wrapper",
            flightCodeElement(flightCodes, outgoingDiversion, props.directRedListFlight.incomingDiversion, showHighlighter = props.showHightLighted), charts)
        ),
        highlightedComponent.map(<.td(^.className := "arrivals__table__flags-column", _)),
        <.td(TerminalContentComponent.airportWrapper(flight.Origin) { proxy: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            proxy().renderEmpty(<.span()),
            proxy().render(ai => {
              val redListCountry = props.indirectRedListPax.isEnabled && isRedListCountry(ai.country, props.viewMode.dayEnd, props.redListUpdates)
              val style: html_<^.TagMod = if (redListCountry) ScalaCssReact.scalacssStyleaToTagMod(ArrivalsPageStylesDefault.redListCountryField) else EmptyVdom
              props.originMapper(flight.Origin, style)
            })
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
        <.td(^.textAlign := "left",
          FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta, props.paxFeedSourceOrder),
          pcpPaxDataQuality.map(dq => DataQualityIndicator(dq, flight.Terminal, "pax-rag")),
          ^.className := s"pcp-pax",
        ),
      )

      val flightFields = firstCells ++ lastCells

      val flightId = flight.uniqueId.toString

      val splitsDataQuality = FlightComponents.splitsDataQuality(flightWithSplits)

      val queueSplits = <.td(
        <.span(^.className := "flex-uniform-size",
          props.splitsQueueOrder.map { q =>
            val pax = if (!flight.Origin.isDomesticOrCta) queuePax.getOrElse(q, 0).toString else "-"
            <.div(pax, ^.className := s"${q.toString.toLowerCase()}-queue-pax arrivals_table__splits__queue-pax")
          }.toTagMod,
        ),
        splitsDataQuality.map(dq => DataQualityIndicator(dq, flight.Terminal, "splits-rag")),
      )

      val cancelledClass = if (flight.isCancelled) " arrival-cancelled" else ""
      val noPcpPax = if (flight.Origin.isCta || outgoingDiversion) " arrival-cta" else ""
      val trClassName = s"${offScheduleClass(flight)} $timeIndicatorClass$cancelledClass$noPcpPax"

      <.tr(
        ^.key := flightId,
        ^.className := trClassName,
        flightFields.toTagMod,
        queueSplits,
        if (props.hasTransfer) <.td(FlightComponents.paxTransferComponent(flight, props.paxFeedSourceOrder)) else EmptyVdom
      )
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def highlightedChips(showNumberOfVisaNationals: Boolean,
                               showRequireAllSelected: Boolean,
                               flaggedAgeGroups: Set[PaxAgeRange],
                               flaggedNationalities: Set[Country],
                               manifestSummary: Option[FlightManifestSummary]): html_<^.VdomNode = {
    <.div(^.minWidth := "150px",
      manifestSummary.map { summary =>
        def generateChip(condition: Boolean, pax: Int, label: String): Option[VdomElement] = {
          if (condition && pax > 0) Option(<.div(^.style := js.Dictionary("paddingBottom" -> "5px"), s"$pax $label"))
          else None
        }

        val flaggedNationalitiesChips: Set[Option[VdomElement]] = flaggedNationalities.map { country =>
          val pax = summary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, s"${country.name} (${country.threeLetterCode}) pax")
        }

        val flaggedAgeGroupsChips: Set[Option[VdomElement]] = flaggedAgeGroups.map { ageRanges =>
          val pax = summary.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, s"pax aged ${ageRanges.title}")
        }

        val visaNationalsChip: Option[VdomElement] = generateChip(showNumberOfVisaNationals, summary.paxTypes.getOrElse(VisaNational, 0), "Visa Nationals")

        val chips: Set[Option[VdomElement]] = flaggedNationalitiesChips ++ flaggedAgeGroupsChips ++ Set(visaNationalsChip) //++ Set(transitChip)

        val conditionsAndChips: Seq[(Boolean, Set[Option[VdomElement]])] = List(
          (flaggedNationalities.nonEmpty, flaggedNationalitiesChips),
          (flaggedAgeGroups.nonEmpty, flaggedAgeGroupsChips),
          (showNumberOfVisaNationals, Set(visaNationalsChip)),
        )

        val trueConditionsAndChips: Seq[(Boolean, Set[Option[VdomElement]])] = conditionsAndChips.filter(_._1)

        if (showRequireAllSelected) {
          if (trueConditionsAndChips.map(_._2).forall(_.exists(_.nonEmpty)))
            trueConditionsAndChips.flatMap(_._2).flatten.toTagMod
          else EmptyVdom
        } else {
          if (chips.exists(_.isDefined)) {
            <.div(chips.flatten.toTagMod)
          } else EmptyVdom
        }
      }.getOrElse(EmptyVdom))
  }

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
