package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.SetPointInTime
import drt.client.services._
import drt.shared.SDateLike
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object DateTimeSelector {

  case class Props()

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

