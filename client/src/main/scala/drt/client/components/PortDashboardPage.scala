package drt.client.components

import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.{Queues, SDateLike}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}

object PortDashboardPage {
  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc)

  case class DisplayPeriod(start: SDateLike, end: SDateLike)

  object DisplayPeriod {
    def apply(start: SDateLike, hours: Int = 3): DisplayPeriod = DisplayPeriod(start, start.addHours(hours))
  }

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P(p => {

      val portCodeQueueOrderTerminals = SPACircuit.connect(_.airportConfig)
      val portStateRCP = SPACircuit.connect(_.portStatePot)

      portCodeQueueOrderTerminals { portMP =>
        <.div(^.className := "terminal-summary-dashboard",
            portMP().render(portConfig => {
            val (queues, paxTypeAndQueueOrder, terminals) = (portConfig.queues, portConfig.paxTypeAndQueueOrder _, portConfig.terminals)
            portStateRCP(portStateMP => {
              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now())
              val periods = List(
                DisplayPeriod(currentPeriodStart),
                DisplayPeriod(currentPeriodStart.addHours(3)),
                DisplayPeriod(currentPeriodStart.addHours(6))
              )

              def displayPeriod = periods(p.dashboardPage.period.getOrElse(0))

              def switchDashboardPeriod(period: Int) = (_: ReactEventFromInput) => {
                GoogleEventTracker.sendEvent("dashboard", "Switch Period", period.toString)
                p.router.set(p.dashboardPage.copy(period = Option(period)))
              }

              <.div(
                <.div(
                  <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
                    periods.zipWithIndex.map {
                      case (p, index) => <.div(
                        ^.className := s"btn btn-primary${if (p == displayPeriod) " active" else ""}",
                        s"${p.start.prettyTime()}-${p.end.prettyTime()}", ^.onClick ==> switchDashboardPeriod(index)
                      )
                    }.toTagMod)),
                terminals.map { terminal =>
                  <.div(
                    <.h3(s"Terminal $terminal"),
                    portStateMP().render(portState => {
                      val portStateForDashboard = portState.windowWithTerminalFilter(displayPeriod.start, displayPeriod.end, portConfig.queues.filterKeys(_ == terminal))
                      val flightsInTerminal = portStateForDashboard.flights.values.toList
                      val terminalCrunchMinutes = portStateForDashboard.crunchMinutes.values.toList
                      val terminalStaffMinutes = portStateForDashboard.staffMinutes.values.toList
                      val terminalQueuesInOrder = Queues.inOrder(queues.getOrElse(terminal, Seq()))

                      DashboardTerminalSummary(DashboardTerminalSummary.Props(
                        flightsInTerminal,
                        terminalCrunchMinutes,
                        terminalStaffMinutes,
                        terminal,
                        paxTypeAndQueueOrder(terminal),
                        terminalQueuesInOrder,
                        displayPeriod.start,
                        displayPeriod.end
                      ))
                    })
                  )
                }.toTagMod
              )
            })
          }
          ))
      }
    })
    .componentWillReceiveProps(p => Callback {
      GoogleEventTracker.sendPageView(s"dashboard${p.nextProps.dashboardPage.period.map(period => s"/$period").getOrElse("")}")
    })
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"dashboard${p.props.dashboardPage.period.map(period => s"/$period").getOrElse("")}")
    })
    .build

  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None)): VdomElement = component(Props(router, dashboardPage))
}
