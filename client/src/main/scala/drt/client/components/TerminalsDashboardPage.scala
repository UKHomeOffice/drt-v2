package drt.client.components

import drt.client.SPAMain.{Loc, TerminalsDashboardLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.{ApiFlightWithSplits, ArrivalHelper, SDateLike}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object TerminalsDashboardPage {

  case class Props(
                    periodStart: Option[String],
                    router: RouterCtl[Loc],
                    dashboardPage: TerminalsDashboardLoc
                  )

  case class DisplayPeriod(start: SDateLike, end: SDateLike)

  object DisplayPeriod {
    def apply(start: SDateLike, hours: Int = 3): DisplayPeriod = DisplayPeriod(start, start.addHours(hours))
  }

  val component = ScalaComponent.builder[Props]("TerminalsDashboard")
    .render_P(p => {

      val portCodeQueueOrderTerminals = SPACircuit.connect(_.airportConfig.map(ac => (ac.queueOrder, ac.terminalNames)))
      val flightsAndMinutes = SPACircuit.connect(_.crunchStatePot)

      portCodeQueueOrderTerminals { portMP =>
        <.div(
          portMP().render(portConfig => {
            val (queueOrder, terminals) = portConfig
            flightsAndMinutes(flightsAndMinutesMP => {
              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now())
              val periods = List(
                DisplayPeriod(currentPeriodStart),
                DisplayPeriod(currentPeriodStart.addHours(3)),
                DisplayPeriod(currentPeriodStart.addHours(6))
              )

              def displayPeriod = periods(p.dashboardPage.period.getOrElse(0))

              def flightWithinPeriod(flight: ApiFlightWithSplits) = DashboardTerminalSummary.flightPcpInPeriod(flight, displayPeriod.start, displayPeriod.end)

              def minuteWithinPeriod(cm: CrunchMinute) = cm.minute >= displayPeriod.start.millisSinceEpoch && cm.minute < displayPeriod.end.millisSinceEpoch

              def switchDashboardPeriod(period: Int) = (_: ReactEventFromInput) => p.router.set(p.dashboardPage.copy(period = Option(period)))

              <.div(
                <.div(^.className := "form-group row",
                  <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
                    periods.zipWithIndex.map {
                      case (p, index) => <.div(
                        ^.className := s"btn btn-primary${if (p == displayPeriod) " active" else ""}",
                        s"${p.start.prettyTime()}-${p.end.prettyTime()}", ^.onClick ==> switchDashboardPeriod(index)
                      )
                    }.toTagMod)),
                  terminals.map { terminalName =>
                    <.div(
                      <.h3(s"Terminal $terminalName"),
                      flightsAndMinutesMP().render(crunchState => {
                        val flightsInTerminal: List[ApiFlightWithSplits] = crunchState
                          .flights
                          .toList
                          .filter(_.apiFlight.Terminal == terminalName)
                          .filter(flightWithinPeriod)
                        val minutesInTerminal =
                          crunchState.crunchMinutes.toList.filter(minuteWithinPeriod).filter(_.terminalName == terminalName)

                        DashboardTerminalSummary(DashboardTerminalSummary.Props(flightsInTerminal, minutesInTerminal, terminalName, queueOrder, displayPeriod.start, displayPeriod.end))
                      })
                    )
                  }.toTagMod
                )
            })
          }
          ))
      }
    })
    .build

  def apply(
             periodStart: Option[String],
             router: RouterCtl[Loc],
             dashboardPage: TerminalsDashboardLoc = TerminalsDashboardLoc(None)
           ): VdomElement = component(Props(periodStart, router, dashboardPage))
}
