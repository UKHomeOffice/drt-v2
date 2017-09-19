package drt.client.components

import diode.data.Pot
import diode.react.ReactConnectProps
import drt.client.SPAMain.Loc
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.{ApiFlightWithSplits, ArrivalHelper}
import japgolly.scalajs.react.component.Generic
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._

object TerminalsDashboardPage {

  case class Props(hours: Int)

  val component = ScalaComponent.builder[Props]("TerminalsDashboard")
    .render_P(p => {
      val now = SDate.now()
      val nowPlusNHours = now.addHours(p.hours)

      def interestingFlight(flight: ApiFlightWithSplits) = BigSummaryBoxes.flightPcpInPeriod(flight, now, nowPlusNHours)



      val terminalsC = SPACircuit.connect(_.airportConfig.map(_.terminalNames))
      val portCodeAndQueueOrder = SPACircuit.connect(_.airportConfig.map(ac => (ac.portCode, ac.queueOrder)))
      val flightsByTerminalC = SPACircuit.connect(_.flightsWithSplitsPot.map(_.flights.filter(interestingFlight).groupBy(_.apiFlight.Terminal)))


      val hours = p.hours
      portCodeAndQueueOrder { portCodeMP =>
        <.div(
          portCodeMP().renderReady(portCodeAndQueue => {
            val (portCode, queueOrder) = portCodeAndQueue
            val bestPaxFN = ArrivalHelper.bestPax _
            val bestSplitPaxFn = BigSummaryBoxes.bestFlightSplitPax(bestPaxFN)
            <.div(terminalsC { terminalsPotMP =>
              <.div(terminalsPotMP().renderReady { terminals =>
                flightsByTerminalC { flightsB =>
                  <.div(
                    <.h2(s"In the next $hours hours"), {
                      terminals.map { t =>
                        val flightsInTerminal: Pot[List[ApiFlightWithSplits]] = flightsB().map(_ (t))
                        <.div(
                          <.h3(s"Terminal $t"),
                          flightsInTerminal.renderReady(flightsAtTerminal => {

                            val flightCount = flightsAtTerminal.length
                            val actPax = BigSummaryBoxes.sumActPax(flightsAtTerminal)
                            val bestPax = BigSummaryBoxes.sumBestPax(bestSplitPaxFn)(flightsAtTerminal).toInt
                            val aggSplits = BigSummaryBoxes.aggregateSplits(bestPaxFN)(flightsAtTerminal)

                            val summaryBoxes = BigSummaryBoxes.SummaryBox(BigSummaryBoxes.Props(flightCount, actPax, bestPax, aggSplits, queueOrder))
                            summaryBoxes
                          }),
                          flightsInTerminal.renderPending((n) => <.span(s"Waiting for flights for $t"))
                        )
                      }.toTagMod
                    })
                }
              })
            })
          }), Debug())
      }
    }
    ).build

  def apply(hours: Int): VdomElement = component(Props(hours))

}
