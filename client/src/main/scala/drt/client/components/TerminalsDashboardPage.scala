package drt.client.components

import diode.data.Pot
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{ApiFlightWithSplits, ArrivalHelper}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

object TerminalsDashboardPage {

  case class Props(hours: Int)

  case class DashboardModel(
                             flights: Seq[ApiFlightWithSplits],
                             crunchMinutes: Set[CrunchMinute],
                             terminal: TerminalName,
                             queues: List[QueueName]
                           )

  val component = ScalaComponent.builder[Props]("TerminalsDashboard")
    .render_P(p => {
      val periodStart = DashboardComponent.windowStart(SDate.now())
      val periodEnd = periodStart.addHours(p.hours)

      def flightWithinPeriod(flight: ApiFlightWithSplits) = BigSummaryBoxes.flightPcpInPeriod(flight, periodStart, periodEnd)

      def minuteWithinPeriod(cm: CrunchMinute) = cm.minute >= periodStart.millisSinceEpoch && cm.minute < periodEnd.millisSinceEpoch


      val portCodeQueueOrderTerminals = SPACircuit.connect(_.airportConfig.map(ac => (ac.portCode, ac.queueOrder, ac.terminalNames)))
      val flightsAndMinutes =   SPACircuit.connect(_.crunchStatePot.map(crunchState =>
        (crunchState.flights.toList.filter(flightWithinPeriod).groupBy(_.apiFlight.Terminal),
          crunchState.crunchMinutes.toList.filter(minuteWithinPeriod).groupBy(_.terminalName))))


      val hours = p.hours
      portCodeQueueOrderTerminals { portMP =>
        <.div(
          portMP().renderReady(portConfig => {
            val (portCode, queueOrder, terminals) = portConfig
            val bestPaxFN = ArrivalHelper.bestPax _
            val bestSplitPaxFn = BigSummaryBoxes.bestFlightSplitPax(bestPaxFN)
            flightsAndMinutes { flightsAndMinutesMP =>
              <.div(
                <.h2(s"In the next $hours hours"), {
                  terminals.map { terminalName =>
                    <.div(
                      <.h3(s"Terminal $terminalName"),
                      flightsAndMinutesMP().renderReady(flightsAndMinutes => {
                        val (flightsInTerminal: List[ApiFlightWithSplits], minutesInTerminal: List[CrunchMinute]) = flightsAndMinutes match {
                          case (flights, minutes) =>
                            (flights.getOrElse(terminalName, Map()), minutes.getOrElse(terminalName, Map()).toList)
                        }
                        DashboardComponent(DashboardComponent.Props(flightsInTerminal, minutesInTerminal, terminalName, queueOrder, periodStart, periodEnd))
                      })
                    )
                  }.toTagMod
                })
            }
          }))
      }
    }
    )
    .build

  def apply(hours: Int): VdomElement = component(Props(hours))
}
