package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.services._
import drt.shared.Crunch.CrunchState
import drt.shared.StaffMovement
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom

import scala.collection.immutable.Seq
import scala.scalajs.js

object Debug {

  case class Props()

  val component = ScalaComponent.builder[Props]("Debug")
    .render_P(p => {
      val staffingRCP = SPACircuit.connect(
        m => (
          m.shiftsRaw,
          m.fixedPointsRaw,
          m.staffMovements,
          m.workloadPot,
          m.queueCrunchResults,
          m.simulationResult,
          m.crunchStatePot,
          m.loadingState
        )
      )
      staffingRCP((staffingMP: ModelProxy[(
        Pot[String],
          Pot[String],
          Pot[Seq[StaffMovement]],
          Pot[Workloads],
          RootModel.PortCrunchResults,
          RootModel.PortSimulationResults,
          Pot[CrunchState],
          LoadingState
        )]) => {
        val (potShifts, potFixedPoints, staffMovements, potWorkloads, tqcr, simulationResult, crunchState, loadingState) = staffingMP()

        if (dom.window.hasOwnProperty("debug")) {
          <.table(
            <.tr(<.th("shifts"), potShifts.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s)))),
            <.tr(<.th("fixed points"), potFixedPoints.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s)))),
            <.tr(<.th("staff movements"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), staffMovements.toString()))),
            <.tr(<.th("Terminal queue Crunch Results"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), tqcr.toString()))),
            <.tr(<.th("Simulation Result"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), simulationResult.toString()))),
            <.tr(<.th("workloads"), potWorkloads.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.toString)))),
            <.tr(<.th("crunch State"), crunchState.renderReady(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.toString)))),
            <.tr(<.th("loading state"), s"$loadingState")
          )
        } else {
          <.div()
        }
      })
    }
    ).build

  def apply(): VdomElement = component(Props())
}
