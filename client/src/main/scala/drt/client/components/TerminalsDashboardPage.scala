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
      val currentPeriodStart = DashboardComponent.windowStart(SDate.now())
      val in3hours = currentPeriodStart.addHours(3)
      val in6hours = currentPeriodStart.addHours(6)
      val in9hours = currentPeriodStart.addHours(9)

      val displayPeriodStart = p.dashboardPage.startTime
        .map(time => {
          val today = currentPeriodStart.toISODateOnly
          DashboardComponent.windowStart(SDate(s"$today $time"))
        })
        .getOrElse(currentPeriodStart)

      val displayPeriodEnd = displayPeriodStart.addHours(3)

      def flightWithinPeriod(flight: ApiFlightWithSplits) = DashboardComponent.flightPcpInPeriod(flight, displayPeriodStart, displayPeriodEnd)

      def minuteWithinPeriod(cm: CrunchMinute) = cm.minute >= displayPeriodStart.millisSinceEpoch && cm.minute < displayPeriodEnd.millisSinceEpoch

      val portCodeQueueOrderTerminals = SPACircuit.connect(_.airportConfig.map(ac => (ac.portCode, ac.queueOrder, ac.terminalNames)))
      val flightsAndMinutes = SPACircuit.connect(_.crunchStatePot.map(crunchState =>
        (crunchState.flights.toList.filter(flightWithinPeriod).groupBy(_.apiFlight.Terminal),
          crunchState.crunchMinutes.toList.filter(minuteWithinPeriod).groupBy(_.terminalName))))

      val next3hours = if (displayPeriodStart.prettyTime() == currentPeriodStart.prettyTime()) "active" else ""
      val hours3to6 = if (displayPeriodStart.prettyTime() == in3hours.prettyTime()) "active" else ""
      val hours6to9 = if (displayPeriodStart.prettyTime() == in6hours.prettyTime()) "active" else ""

      def switchDashboardPeriod(time: String) = p.router.set(p.dashboardPage.copy(startTime = Option(time)))

      def periodNext3 = (_: ReactEventFromInput) => switchDashboardPeriod(currentPeriodStart.prettyTime())

      def period3to6 = (_: ReactEventFromInput) => switchDashboardPeriod(in3hours.prettyTime())

      def period6to9 = (_: ReactEventFromInput) => switchDashboardPeriod(in6hours.prettyTime())

      portCodeQueueOrderTerminals { portMP =>
        <.div(
          portMP().renderReady(portConfig => {
            val (portCode, queueOrder, terminals) = portConfig
            val bestPaxFN = ArrivalHelper.bestPax _
            val bestSplitPaxFn = BigSummaryBoxes.bestFlightSplitPax(bestPaxFN)
            flightsAndMinutes { flightsAndMinutesMP =>
              <.div(
                <.div(^.className := "form-group row",
                  <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
                    <.div(^.className := s"btn btn-primary $next3hours", s"${currentPeriodStart.prettyTime()}-${in3hours.prettyTime()}", ^.onClick ==> periodNext3),
                    <.div(^.className := s"btn btn-primary $hours3to6", s"${in3hours.prettyTime()}-${in6hours.prettyTime()}", ^.onClick ==> period3to6),
                    <.div(^.className := s"btn btn-primary $hours6to9", s"${in6hours.prettyTime()}-${in9hours.prettyTime()}", ^.onClick ==> period6to9))),
                terminals.map { terminalName =>
                  <.div(
                    <.h3(s"Terminal $terminalName"),
                    flightsAndMinutesMP().renderReady(flightsAndMinutes => {
                      val (flightsInTerminal: List[ApiFlightWithSplits], minutesInTerminal: List[CrunchMinute]) = flightsAndMinutes match {
                        case (flights, minutes) =>
                          (flights.getOrElse(terminalName, Map()), minutes.getOrElse(terminalName, Map()).toList)
                      }
                      DashboardComponent(DashboardComponent.Props(flightsInTerminal, minutesInTerminal, terminalName, queueOrder, currentPeriodStart, in3hours))
                    })
                  )
                }.toTagMod
              )
            }
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
