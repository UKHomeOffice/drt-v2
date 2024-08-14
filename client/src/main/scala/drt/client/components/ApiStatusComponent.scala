package drt.client.components

import diode.data.Pot
import drt.client.components.FlightComponents.DataQuality
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

case class ApiFeedStatus(totalLanded: Int, withApi: Int, withValidApi: Int) {
  val receivedPct: Option[Double] = if (totalLanded > 0) Option((withApi.toDouble / totalLanded) * 100) else None
  val validPct: Option[Double] = if (withApi > 0) Option((withValidApi.toDouble / withApi) * 100) else None
}

object ApiFeedStatus {
  def apply(flights: Iterable[ApiFlightWithSplits],
            nowMillis: MillisSinceEpoch,
            hasLiveFeed: Boolean,
            paxFeedSourceOrder: List[FeedSource],
           ): ApiFeedStatus = {
    val landed = flights.filter(fws => fws.apiFlight.bestArrivalTime(considerPredictions = true) <= nowMillis)
    val international = landed.filterNot(fws => fws.apiFlight.Origin.isDomesticOrCta)
    val withNonZeroPax = international.filter(fws => fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).exists(_ > 0) || !hasLiveFeed)
    val apiFlightCount = withNonZeroPax.count(_.hasApi)
    val validApiCount = withNonZeroPax.count(_.hasValidApi)
    ApiFeedStatus(withNonZeroPax.size, apiFlightCount, validApiCount)
  }
}

object ApiStatusComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(canValidate: Boolean, terminal: Terminal)

  case class ApiDataQuality(percentage: Option[Int], label: String, info: String) extends DataQuality {
    override val text: String = s"$label: ${percentage.map(_.toString).getOrElse("n/a")}%"
    override val `type`: String = percentage match {
      case Some(p) if p < 80 => "error"
      case Some(p) if p < 90 => "warning"
      case Some(_) => "success"
      case None => "info"
    }
    override val maybeTooltip: Option[String] = Option(info)
  }

  private val receivedInfoText = "The percentage of landed flights that have received API data"
  private val validatedInfoText = "The percentage of received API data that is within a 5% threshold of the port operator's total pax count"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ApiStatus")
    .render_P { props =>
      case class FlightsAndPaxFeedOrder(flights: Pot[Iterable[ApiFlightWithSplits]], paxFeedSourceOrder: List[FeedSource])
      val terminalFlightsProxy = SPACircuit.connect { m =>
        FlightsAndPaxFeedOrder(m.portStatePot.map(_.flights.values.filter(_.apiFlight.Terminal == props.terminal)), m.paxFeedSourceOrder)
      }

      terminalFlightsProxy { terminalFlightsPot =>
        <.div(
          terminalFlightsPot().flights.renderReady { flights =>
            val apiFeedStatus = ApiFeedStatus(flights, SDate.now().millisSinceEpoch, props.canValidate, terminalFlightsPot().paxFeedSourceOrder)

            <.div(^.className := "status-bar-item", "API (Advance Passenger Information)",
              DataQualityIndicator(ApiDataQuality(apiFeedStatus.receivedPct.map(_.round.toInt), "Received", receivedInfoText), props.terminal, "api-received", icon = true),
              if (props.canValidate)
                DataQualityIndicator(ApiDataQuality(apiFeedStatus.validPct.map(_.round.toInt), "Valid", validatedInfoText), props.terminal, "api-valid", icon = true)
              else EmptyVdom,
            )
          },
          terminalFlightsPot().flights.renderPending(_ =>
            <.div(^.className := "status-bar-item", "API (Advance Passenger Information)",
              DataQualityIndicator(ApiDataQuality(None, "Received", receivedInfoText), props.terminal, "api-received", icon = true),
            )
          ),
        )
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
