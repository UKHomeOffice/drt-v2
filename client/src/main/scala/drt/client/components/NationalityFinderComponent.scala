package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.{Nationality, RedList}
import drt.shared.api.PassengerInfoSummary
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._

object NationalityFinderComponent {
  def isRedListCountry(country: String): Boolean = redList.keys.exists(_.toLowerCase == country.toLowerCase)

  val log: Logger = LoggerFactory.getLogger(getClass.getName)
  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("FlightChart")
    .render_P(p => {

      val nats = p.ofInterest.map(n => p.passengerInfo.nationalities.getOrElse(n, 0)).sum
      <.span(
        if (nats > 0)
          NationalityFinderChartComponent(
            NationalityFinderChartComponent.Props(
              p.passengerInfo.nationalities.filter {
                case (nat, _) => p.ofInterest.toList.contains(nat)
              },
              <.span(^.className := "badge", nats)
            )
          )
        else
          <.span(nats),

      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)

  case class Props(
                    ofInterest: Iterable[Nationality],
                    passengerInfo: PassengerInfoSummary
                  )

  val redList: Map[String, String] = RedList.countryToCode

  val redListNats: Iterable[Nationality] = redList.values.map(Nationality(_))

}


