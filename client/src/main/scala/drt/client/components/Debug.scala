package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.services._
import drt.shared.{FixedPointAssignments, PortState, ShiftAssignments, StaffMovements}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import org.scalajs.dom

import scala.scalajs.js

object Debug {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Debug")
    .render_P { _ =>
      val staffingRCP = SPACircuit.connect(
        m => (
          m.dayOfShiftAssignments,
          m.fixedPoints,
          m.staffMovements,
          m.portStatePot,
          m.loadingState
        )
      )
      staffingRCP((staffingMP: ModelProxy[(
        Pot[ShiftAssignments],
          Pot[FixedPointAssignments],
          Pot[StaffMovements],
          Pot[PortState],
          LoadingState
        )]) => {
        val (potShifts, potFixedPoints, staffMovements, portState, loadingState) = staffingMP()

        if (dom.window.hasOwnProperty("debug")) {
          <.table(
            <.tr(<.th("shifts"), potShifts.render(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.assignments.map(_.toString).mkString("\n"))))),
            <.tr(<.th("fixed points"), potFixedPoints.render(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.assignments.map(_.toString).mkString("\n"))))),
            <.tr(<.th("staff movements"), <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), staffMovements.toString()))),
            <.tr(<.th("crunch State"), portState.render(s => <.td(<.pre(^.style := js.Dictionary("overflow" -> "auto", "height" -> "200px"), s.toString)))),
            <.tr(<.th("loading state"), s"$loadingState")
          )
        } else {
          <.div()
        }
      })
    }.build

  def apply(): VdomElement = component(Props())
}
