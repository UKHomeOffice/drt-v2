package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{SaveStaffMovements, SetPointInTime}
import drt.client.services._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{SDateLike, StaffMovement}
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom

import scala.collection.immutable.Seq
import scala.scalajs.js

object DateTimeSelector {

  case class Props()

  //  def selectFromRange(range: Range, defaultValue: Int) = {
  //    <.select(
  //      ^.defaultValue := applyRounding(defaultValue),
  //      ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))
  //        ),
  //      range.map(x => <.option(^.value := x, f"$x%02d")).toTagMod)
  //  }

  val component = ScalaComponent.builder[Props]("Debug")
    .render_P(p => {
      val pointInTimeRCP = SPACircuit.connect(
        m => m.pointInTime
      )
      pointInTimeRCP((pointInTimeMP: ModelProxy[Option[SDateLike]]) => {
        val pointInTime = pointInTimeMP()

        <.div(
          <.input.text(^.onChange ==>((e: ReactEventFromInput) => pointInTimeMP.dispatchCB(SetPointInTime(e.target.value)))
          ))
      })
    }
    ).build

  def apply(): VdomElement = component(Props())
}

