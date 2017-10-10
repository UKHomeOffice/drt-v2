package drt.client.components

import diode.data.{Pending, Pot}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, TimeRangeHours}
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object TerminalComponent {

  case class Props(terminalName: TerminalName)

  case class TerminalModel(
                            crunchStatePot: Pot[CrunchState],
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            pointInTime: Option[SDateLike],
                            timeRangeHours: TimeRangeHours
                          )

  def render(props: Props) = {
    val modelRCP = SPACircuit.connect(model => TerminalModel(
      model.crunchStatePot,
      model.airportConfig,
      model.airportInfos.getOrElse(props.terminalName, Pending()),
      model.pointInTime,
      model.timeRangeFilter
    ))

    modelRCP(modelMP => {
      val model = modelMP.value
      <.div(
        model.airportConfig.renderReady(airportConfig => {
          val terminalContentProps = TerminalContentComponent.Props(
            model.crunchStatePot,
            airportConfig,
            props.terminalName,
            model.airportInfos,
            model.timeRangeHours,
            () => model.pointInTime.getOrElse(SDate.midnightThisMorning())
          )
          <.div(
            SnapshotSelector(SnapshotSelector.Props(model.pointInTime, props.terminalName)),
            TerminalContentComponent(terminalContentProps)
          )
        }
        )
      )
    })
  }

  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Terminal")
    .renderPS(($, props, state) => {
      val modelRCP = SPACircuit.connect(model => TerminalModel(
        model.crunchStatePot,
        model.airportConfig,
        model.airportInfos.getOrElse(props.terminalName, Pending()),
        model.pointInTime,
        model.timeRangeFilter
      ))
      modelRCP(modelMP => {
        val model = modelMP.value
        <.div(model.airportConfig.renderReady(airportConfig => {
          <.div(
            TerminalDisplayModeComponent(TerminalDisplayModeComponent.Props(
              model.crunchStatePot,
              airportConfig,
              props.terminalName,
              model.airportInfos,
              model.timeRangeHours,
              model.pointInTime.getOrElse(SDate.midnightThisMorning())
            )))
        }))
      })
    })
    .componentDidUpdate(p => Callback.log("Updating Terminal Component"))
    .componentDidMount(p => Callback.log("Updating Terminal Component"))
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}


