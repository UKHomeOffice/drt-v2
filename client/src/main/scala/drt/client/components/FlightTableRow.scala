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
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist._
import drt.shared.splits.ApiSplitsToSplitRatio
import io.kinoplan.scalajs.react.material.ui.core.MuiChip
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
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
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.SDateLike

import scala.scalajs.js

object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = PortCode => VdomNode

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShares: Seq[Arrival],
                   idx: Int,
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
                   walkTimes: WalkTimes,
                   flaggedNationalities: Set[Country],
                   manifestSummary: Option[FlightManifestSummary],
                   paxFeedSourceOrder: List[FeedSource],
                  ) extends UseValueEq

  implicit val propsReuse: Reusability[Props] = Reusability {
    (a, b) => a.flightWithSplits.lastUpdated == b.flightWithSplits.lastUpdated &&
      a.manifestSummary == b.manifestSummary &&
      a.flaggedNationalities == b.flaggedNationalities
  }

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .render_P { props =>
      val isMobile = dom.window.innerWidth < 800
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.flightCodeString :: codeShares.map(_.flightCodeString).toList

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
        "Predicted" -> (if (props.airportConfig.useTimePredictions) flight.predictedTouchdown else None),
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

      val charts = (flightWithSplits.hasValidApi, props.manifestSummary) match {
        case (true, Some(manifestSummary)) =>
          <.div(^.className := "arrivals__table__flight-code__info",
            FlightChartComponent(FlightChartComponent.Props(manifestSummary)))
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
        if (props.flaggedNationalities.nonEmpty)
          <.td(^.className := "arrivals__table__flags-column", nationalityChips(props.flaggedNationalities, props.manifestSummary))
        else EmptyVdom,
        <.td(gateOrStand(flight, props.airportConfig.defaultWalkTimeMillis(flight.Terminal), props.directRedListFlight.paxDiversion, props.walkTimes)),
        <.td(^.className := "no-wrap", if (isMobile) flight.displayStatusMobile.description else flight.displayStatus.description),
        <.td(maybeLocalTimeWithPopup(Option(flight.Scheduled))),
        <.td(expectedContent),
      )
      val lastCells = List[TagMod](
        <.td(pcpTimeRange(flightWithSplits, props.airportConfig.firstPaxOffMillis, props.walkTimes, props.paxFeedSourceOrder), ^.className := "arrivals__table__flight-est-pcp"),
        <.td(^.className := s"pcp-pax ${paxFeedSourceClass(flightWithSplits.apiFlight.bestPaxEstimate(props.paxFeedSourceOrder))}", FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta, props.paxFeedSourceOrder)),
      )

      val flightFields = firstCells ++ lastCells

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      val flightId = flight.uniqueId.toString

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

  private def nationalityChips(flaggedNationalities: Set[Country], manifestSummary: Option[FlightManifestSummary]): html_<^.VdomNode = {
    manifestSummary.map { summary =>
      <.div(
        ^.style := js.Dictionary("display" -> "flex", "flexWrap" -> "wrap", "gap" -> "8px"),
        flaggedNationalities
          .map { country =>
            val pax = summary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0)
            if (pax > 0) Option(MuiChip(
              label = VdomNode(s"${country.threeLetterCode} ($pax)"),
              sx = SxProps(Map(
                "color" -> "#FFFFFF",
                "backgroundColor" -> "#316CCC",
              ))
            )())
            else None
          }
          .collect {
            case Some(chip) => chip
          }
          .toTagMod
      )
    }.getOrElse(EmptyVdom)
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

  def offScheduleClass(arrival: Arrival, considerPredictions: Boolean): String = {
    val eta = arrival.bestArrivalTime(considerPredictions)
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
