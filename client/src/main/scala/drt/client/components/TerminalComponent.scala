package drt.client.components

import diode.data.{Pending, Pot}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services._
import drt.shared.CrunchApi.{CrunchState, ForecastPeriodWithHeadlines}
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import scala.collection.immutable

object TerminalComponent {

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc])

  case class TerminalModel(
                            crunchStatePot: Pot[CrunchState],
                            forecastPeriodPot: Pot[ForecastPeriodWithHeadlines],
                            potShifts: Pot[String],
                            potMonthOfShifts: Pot[MonthOfRawShifts],
                            potFixedPoints: Pot[String],
                            potStaffMovements: Pot[immutable.Seq[StaffMovement]],
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            timeRangeHours: TimeRangeHours,
                            loadingState: LoadingState,
                            showActuals: Boolean
                          )

  implicit val pageReuse: Reusability[TerminalPageTabLoc] = Reusability.caseClass[TerminalPageTabLoc]
  implicit val propsReuse: Reusability[Props] = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Terminal")
    .render_P(props => {
      val modelRCP = SPACircuit.connect(model => TerminalModel(
        model.crunchStatePot,
        model.forecastPeriodPot,
        model.shiftsRaw,
        model.monthOfShifts,
        model.fixedPointsRaw,
        model.staffMovements,
        model.airportConfig,
        model.airportInfos.getOrElse(props.terminalPageTab.terminal, Pending()),
        model.timeRangeFilter,
        model.loadingState,
        model.showActualIfAvailable
      ))
      modelRCP(modelMP => {
        val model = modelMP.value
        <.div(model.airportConfig.render(airportConfig => {
          <.div(
            TerminalDisplayModeComponent(TerminalDisplayModeComponent.Props(
              model.crunchStatePot,
              model.forecastPeriodPot,
              model.potShifts,
              model.potMonthOfShifts,
              model.potFixedPoints,
              model.potStaffMovements,
              airportConfig,
              props.terminalPageTab,
              model.airportInfos,
              model.timeRangeHours,
              props.router,
              model.loadingState,
              model.showActuals
            )
            ))
        }))
      })
    })
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}
