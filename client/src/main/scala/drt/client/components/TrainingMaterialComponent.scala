package drt.client.components

import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{CtorType, ScalaComponent}

object TrainingMaterialComponent {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TrainingMaterialComponent")
    .render_P(_ =>
      <.div(^.className := "training-material", <.h3("Training Material"), TrainingMaterialDetailComponent())
    )
    .build

  def apply(): VdomElement = component(Props())
}

object TrainingMaterialDetailComponent {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TrainingMaterialDetailComponent")
    .render_P { _ =>
      val comingSoon = List(
        <.p(s"Coming soon...") ,
        <.p(s"Meanwhile, drop-ins are being conducted. You can book a drop-in by clicking on the 'Book a Drop-in Session' tab.")
      ).toTagMod

      <.div(comingSoon)
    }
    .build

  def apply(): VdomElement = component(Props())

}
