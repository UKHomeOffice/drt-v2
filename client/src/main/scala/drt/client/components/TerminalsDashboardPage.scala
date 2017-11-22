package drt.client.components

import drt.client.SPAMain.{Loc, TerminalsDashboardLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.{ApiFlightWithSplits, ArrivalHelper}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object TerminalsDashboardPage {

  case class Props(
                    periodStart: Option[String],
                    router: RouterCtl[Loc],
                    dashboardPage: TerminalsDashboardLoc
                  )

  val component = ScalaComponent.builder[Props]("TerminalsDashboard")
    .render_P(p => {

      val portCodeQueueOrderTerminals = SPACircuit.connect(_.airportConfig.map(ac => (ac.queueOrder, ac.terminalNames)))
      val flightsAndMinutes = SPACircuit.connect(_.crunchStatePot)

      portCodeQueueOrderTerminals { portMP =>
        <.div(
          portMP().renderReady(portConfig => {
            val (queueOrder, terminals) = portConfig
            val bestPaxFN = ArrivalHelper.bestPax _
            flightsAndMinutes(flightsAndMinutesMP => {
              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now())
              val in3hours = currentPeriodStart.addHours(3)
              val in6hours = currentPeriodStart.addHours(6)
              val in9hours = currentPeriodStart.addHours(9)

              def displayPeriodStart = p.dashboardPage.startTime
                .map(time => {
                  val today = currentPeriodStart.toISODateOnly
                  DashboardTerminalSummary.windowStart(SDate(s"$today $time"))
                })
                .getOrElse(currentPeriodStart)

              val displayPeriodEnd = displayPeriodStart.addHours(3)

              def flightWithinPeriod(flight: ApiFlightWithSplits) = DashboardTerminalSummary.flightPcpInPeriod(flight, displayPeriodStart, displayPeriodEnd)

              def minuteWithinPeriod(cm: CrunchMinute) = cm.minute >= displayPeriodStart.millisSinceEpoch && cm.minute < displayPeriodEnd.millisSinceEpoch


              val next3hours = if (displayPeriodStart.prettyTime() == currentPeriodStart.prettyTime()) "active" else ""
              val hours3to6 = if (displayPeriodStart.prettyTime() == in3hours.prettyTime()) "active" else ""
              val hours6to9 = if (displayPeriodStart.prettyTime() == in6hours.prettyTime()) "active" else ""

              def switchDashboardPeriod(time: String) = p.router.set(p.dashboardPage.copy(startTime = Option(time)))

              def periodNext3 = (_: ReactEventFromInput) => switchDashboardPeriod(currentPeriodStart.prettyTime())

              def period3to6 = (_: ReactEventFromInput) => switchDashboardPeriod(in3hours.prettyTime())

              def period6to9 = (_: ReactEventFromInput) => switchDashboardPeriod(in6hours.prettyTime())

              <.div(
                <.div(^.className := "form-group row",
                  <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
                    <.div(^.className := s"btn btn-primary $next3hours", s"${currentPeriodStart.prettyTime()}-${in3hours.prettyTime()}", ^.onClick ==> periodNext3),
                    <.div(^.className := s"btn btn-primary $hours3to6", s"${in3hours.prettyTime()}-${in6hours.prettyTime()}", ^.onClick ==> period3to6),
                    <.div(^.className := s"btn btn-primary $hours6to9", s"${in6hours.prettyTime()}-${in9hours.prettyTime()}", ^.onClick ==> period6to9))),
                terminals.map { terminalName =>
                  <.div(
                    <.h3(s"Terminal $terminalName"),
                    flightsAndMinutesMP().renderReady(crunchState => {
                      val flightsInTerminal: List[ApiFlightWithSplits] = crunchState
                        .flights
                        .toList
                        .filter(_.apiFlight.Terminal == terminalName)
                        .filter(flightWithinPeriod)
                      val minutesInTerminal =
                        crunchState.crunchMinutes.toList.filter(minuteWithinPeriod).filter(_.terminalName == terminalName)

                      DashboardTerminalSummary(DashboardTerminalSummary.Props(flightsInTerminal, minutesInTerminal, terminalName, queueOrder, displayPeriodStart, displayPeriodEnd))
                    })
                  )
                }.toTagMod
              )
            })
          }))
      }
    })
    .build

  def apply(
             periodStart: Option[String],
             router: RouterCtl[Loc],
             dashboardPage: TerminalsDashboardLoc = TerminalsDashboardLoc(None)
           ): VdomElement = component(Props(periodStart, router, dashboardPage))
}
