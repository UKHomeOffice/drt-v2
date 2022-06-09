package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits

case class ApiFeedStatus(totalLanded: Int, withApi: Int, withValidApi: Int) {
  val receivedPct: Option[Double] = if (totalLanded > 0) Option((withApi.toDouble / totalLanded) * 100) else None
  val validPct: Option[Double] = if (withApi > 0) Option((withValidApi.toDouble / withApi) * 100) else None
}

object ApiFeedStatus {
  def apply(flights: Iterable[ApiFlightWithSplits], nowMillis: MillisSinceEpoch, timeToChox: Int, considerPredictions: Boolean): ApiFeedStatus = {
    val landedInternationalWithPax = flights
      .filter(fws => fws.apiFlight.bestArrivalTime(timeToChox, considerPredictions) <= nowMillis)
      .filterNot(fws => fws.apiFlight.Origin.isDomesticOrCta)
      .filter(fws => fws.apiFlight.ActPax.exists(_ > 0))
    val apiFlightCount = landedInternationalWithPax.count(_.hasApi)
    val validApiCount = landedInternationalWithPax.count(_.hasValidApi)
    ApiFeedStatus(landedInternationalWithPax.size, apiFlightCount, validApiCount)
  }
}

object ApiStatusComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(canValidate: Boolean, timeToChox: Int, considerPredictions: Boolean)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ApiStatus")
    .render_P { props =>
      val value = SPACircuit.connect(m => m.portStatePot)
      value { portStatePot =>
        <.div(
          portStatePot().renderReady { ps =>
            val apiFeedStatus = ApiFeedStatus(ps.flights.values, SDate.now().millisSinceEpoch, props.timeToChox, props.considerPredictions)

            def ragClass(pct: Option[Double]): String = pct match {
              case Some(red) if red < 80 => "red"
              case Some(amber) if amber < 90 => "amber"
              case Some(_) => "green"
              case None => "grey"
            }

            def statToString(maybeStat: Option[Double]): String = maybeStat match {
              case Some(stat) => s"${stat.toInt}%"
              case None => "n/a"
            }

            <.div(^.className := "status-bar-item", "API",
              <.div(^.className := s"api-status ${ragClass(apiFeedStatus.receivedPct)}", s"received: ${statToString(apiFeedStatus.receivedPct)}"),
              if (props.canValidate)
                <.div(^.className := s"api-status ${ragClass(apiFeedStatus.validPct)}", s"valid: ${statToString(apiFeedStatus.validPct)}")
              else EmptyVdom,
            )
          })
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
