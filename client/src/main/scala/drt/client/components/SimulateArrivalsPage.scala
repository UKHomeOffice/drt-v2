package drt.client.components

import drt.client.SPAMain
import drt.client.modules.GoogleEventTracker
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}


object SimulateArrivalsPage {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalSimulations")
    .render_P { _ =>

      <.div(<.h2("Arrival Simulations"))
      <.form(
        ^.action := SPAMain.absoluteUrl("desk-rec-simulation"),
        ^.method := "post",
        ^.encType := "multipart/form-data",
        <.div(^.className := "form-group",
          <.label(^.htmlFor := "arrivals-file", "Arrivals File"),
          <.input(^.tpe := "file", ^.name := "arrivals-file", ^.id := "arrivals-file", ^.required := true)
        ),
        <.div(^.className := "form-group",
          <.label(^.htmlFor := "passenger-weighting", "Passenger weighting"),
          <.input(^.tpe := "number", ^.step :="0.1", ^.name := "passenger-weighting", ^.id := "passenger-weighting")
        ),
        <.button(^.tpe := "Submit", ^.className := "btn btn-primary", "Submit")
      )

    }.componentDidMount(_ => Callback {
    GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
  }).build

  def apply(): VdomElement = component(Props())

}
