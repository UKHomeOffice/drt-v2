package drt.client.components

import diode.UseValueEq
import drt.client.components.FlightComponents.SplitsDataQuality.{CarrierData, HistoricalCarrierData, TerminalAverageData, TrustedCarrierData}
import drt.shared.redlist.DirectRedListFlight
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.TrendingFlat
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.{Div, Span}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, PaxSource}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports._


object FlightComponents {

  trait DataQuality {
    val `type`: String
    val text: String
    val maybeTooltip: Option[String] = None
  }

  trait PcpPaxDataQuality extends DataQuality {
    val `type`: String
    val text: String
  }
  object PcpPaxDataQuality {
    object TrustedPortData extends PcpPaxDataQuality {
      val `type`: String = "success"
      val text: String = "Port live data"
      override val maybeTooltip: Option[String] = Option("Live data from the port operator")
    }
    object CarrierData extends PcpPaxDataQuality {
      val `type`: String = "info"
      val text: String = "Carrier data"
      override val maybeTooltip: Option[String] = Option("Advance Passenger Information (API) from the carrier")
    }
    object PortForecastData extends PcpPaxDataQuality {
      val `type`: String = "warning"
      val text: String = "Port forecast"
      override val maybeTooltip: Option[String] = Option("Data from the port operator")
    }
    object MlData extends PcpPaxDataQuality {
      val `type`: String = "warning"
      val text: String = "DRT forecast"
      override val maybeTooltip: Option[String] = Option("Machine learning from multiple information sources and historical trends")
    }
    object HistoricalData extends PcpPaxDataQuality {
      val `type`: String = "warning"
      val text: String = "Estimate"
      override val maybeTooltip: Option[String] = Option("Based on an historical average or an estimated load factor")
    }
    object AclData extends PcpPaxDataQuality {
      val `type`: String = "error"
      val text: String = "Estimate"
      override val maybeTooltip: Option[String] = Option("Based on an historical average or an estimated load factor")
    }

  }
  def paxFeedSourceClass(paxSource: PaxSource, isDomesticOrCta: Boolean): Option[PcpPaxDataQuality] =
    if (isDomesticOrCta)
      None
    else
      paxSource.feedSource match {
        case LiveFeedSource => Option(PcpPaxDataQuality.TrustedPortData)
        case ApiFeedSource => Option(PcpPaxDataQuality.CarrierData)
        case ForecastFeedSource => Option(PcpPaxDataQuality.PortForecastData)
        case HistoricApiFeedSource => Option(PcpPaxDataQuality.HistoricalData)
        case MlFeedSource => Option(PcpPaxDataQuality.MlData)
        case AclFeedSource => Option(PcpPaxDataQuality.AclData)
        case _ => Option(PcpPaxDataQuality.AclData)
      }

  def paxComp(flightWithSplits: ApiFlightWithSplits,
              directRedListFlight: DirectRedListFlight,
              noPcpPax: Boolean,
              paxFeedSourceOrder: List[FeedSource]): TagMod = {
    val isNotApiData = if (flightWithSplits.hasValidApi) "" else "notApiData"
    val noPcpPaxClass = if (noPcpPax || directRedListFlight.outgoingDiversion) "arrivals__table__flight__no-pcp-pax" else ""

    val diversionClass =
      if (directRedListFlight.incomingDiversion) "arrivals__table__flight__pcp-pax__incoming"
      else if (directRedListFlight.outgoingDiversion) "arrivals__table__flight__pcp-pax__outgoing"
      else ""

    val pcpPaxNumber = if (!flightWithSplits.apiFlight.Origin.isDomesticOrCta)
      s"${flightWithSplits.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).map(_.toString).getOrElse("n/a")}"
    else "-"

    <.div(
      ^.className := s"arrivals__table__flight__pcp-pax $diversionClass $isNotApiData underline",
      <.span(Tippy.describe(
        paxNumberSources(flightWithSplits), <.span(^.className := s"pcp-pax-value $noPcpPaxClass", pcpPaxNumber))
      ),
      if (directRedListFlight.paxDiversion) {
        val incomingTip =
          if (directRedListFlight.incomingDiversion) s"Passengers diverted from ${flightWithSplits.apiFlight.Terminal}"
          else "Passengers diverted to red list terminal"
        Tippy.describe(<.span(incomingTip), MuiIcons(TrendingFlat)())
      } else <.span(),
    )
  }

  trait SplitsDataQuality extends DataQuality {
    val `type`: String
    val text: String
  }

  object SplitsDataQuality {

    object TrustedCarrierData extends SplitsDataQuality {
      val `type`: String = "success"
      val text: String = "Verified carrier data"
      override val maybeTooltip: Option[String] = Option("Based on Advance Passenger Information (API) from the carrier")
    }

    object CarrierData extends SplitsDataQuality {
      val `type`: String = "info"
      val text: String = "Carrier data"
      override val maybeTooltip: Option[String] = Option("Advance Passenger Information (API) from the carrier")
    }

    object HistoricalCarrierData extends SplitsDataQuality {
      val `type`: String = "warning"
      val text: String = "Past carrier data"
      override val maybeTooltip: Option[String] = Option("Based on historical Advance Passenger Information (API) from this flight or route")
    }

    object TerminalAverageData extends SplitsDataQuality {
      val `type`: String = "error"
      val text: String = "Terminal average data"
      override val maybeTooltip: Option[String] = Option("Based on historical average pax splits for this terminal")
    }
  }

  def splitsDataQuality(flightWithSplits: ApiFlightWithSplits): Option[SplitsDataQuality] = {
    if (flightWithSplits.apiFlight.Origin.isDomesticOrCta)
      None
    else flightWithSplits.bestSplits.map(_.source) match {
      case Some(SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages) if flightWithSplits.hasLivePaxSource => Option(TrustedCarrierData)
      case Some(SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages) if !flightWithSplits.hasLivePaxSource => Option(CarrierData)
      case Some(SplitSources.Historical) => Option(HistoricalCarrierData)
      case _ => Option(TerminalAverageData)
    }
  }

  def paxNumberSources(flight: ApiFlightWithSplits): VdomTagOf[Span] = {
    val paxSources = flight.apiFlight.PassengerSources.toList.sortBy(_._1.name)
      .map {
        case (feedSource, pax) =>
          (pax.actual, pax.transit) match {
            case (Some(actual), Some(transit)) if transit > 0 =>
              Option(<.p(^.key := feedSource.id, s"${feedSource.displayName} - ${pax.getPcpPax.map(_.toString).getOrElse("")} (${actual.toString} - ${transit.toString} transit)"))
            case (Some(actual), _) =>
              Option(<.p(^.key := feedSource.id, s"${feedSource.displayName} - $actual"))
            case _ =>
              Option(<.p(^.key := feedSource.id, s"${feedSource.displayName} - n/a"))
          }
      }
      .collect { case Some(source) => source }

    val maxPax = <.p(^.key := "capacity", s"Seats: ${flight.apiFlight.MaxPax.getOrElse("-")}")
    <.span((paxSources :+ maxPax).toVdomArray)
  }

  def paxTransferComponent(flight: Arrival, paxFeedSourceOrder: List[FeedSource]): VdomTagOf[Div] = {
    val transPax = if (flight.Origin.isCta) "-" else flight.bestPaxEstimate(paxFeedSourceOrder).passengers.transit.getOrElse("-")
    <.div(
      s"$transPax"
    )
  }

  def paxTypeAndQueueString(ptqc: PaxTypeAndQueue) = s"${ptqc.displayName}"

  object SplitsGraph {

    case class Props(splitTotal: Int, splits: Iterable[(PaxTypeAndQueue, Int)]) extends UseValueEq

    def splitsGraphComponentColoured(props: Props): TagOf[Div] = {
      import props._
      val maxSplit = props.splits.map(_._2).max
      <.div(
        ^.className := "dashboard-summary__pax",
        <.div(^.className := "dashboard-summary__total-pax", s"${props.splitTotal} Pax"),
        <.div(^.className := "dashboard-summary__splits-graph-bars", ^.tabIndex := 0,
          splits.map {
            case (paxTypeAndQueue, paxCount) =>
              val percentage = ((paxCount.toDouble / maxSplit) * 100).toInt
              val label = paxTypeAndQueueString(paxTypeAndQueue)
              <.div(^.className := s"dashboard-summary__splits-graph-bar dashboard-summary__splits-graph-bar--${paxTypeAndQueue.queueType.toString.toLowerCase}",
                ^.height := s"$percentage%",
                ^.title := s"$label",
                ^.aria.label := s"$label passenger percentage is $percentage.",
                ^.tabIndex := 0
              )
          }.toTagMod
        )
      )
    }
  }
}
