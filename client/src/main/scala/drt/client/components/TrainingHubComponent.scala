package drt.client.components

import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services._
import drt.client.spa.TrainingHubPageMode
import drt.client.spa.TrainingHubPageModes.{DropInBooking, TrainingMaterial}
import drt.shared.{DropIn, DropInRegistration}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.AirportConfig

object TrainingHubComponent {

  val log: Logger = LoggerFactory.getLogger("TrainingHubComponent")

  case class Props(trainingHubLoc: TrainingHubLoc,
                   router: RouterCtl[Loc],
                   loggedInUserPot: Pot[LoggedInUser],
                   airportConfigPot: Pot[AirportConfig],
                  ) extends FastEqLowPri

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.trainingHubLoc == b.trainingHubLoc)

  private case class TrainingModel(airportConfig: Pot[AirportConfig],
                                   loggedInUserPot: Pot[LoggedInUser],
                                   dropIns: Pot[Seq[DropIn]],
                                   dropInRegistrations : Pot[Seq[DropInRegistration]]
                                  ) extends UseValueEq

  private val activeClass = "active"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TrainingHubComponent")
    .render_P { props =>
      val modelRCP = SPACircuit.connect(model => TrainingModel(
        airportConfig = props.airportConfigPot,
        loggedInUserPot = props.loggedInUserPot,
        dropIns = model.dropIns,
        dropInRegistrations = model.dropInRegistrations
      ))

      <.div(
        modelRCP(modelMP => {
          val model: TrainingModel = modelMP()
            <.div(
              <.div(^.className := "terminal-nav-wrapper", trainingTabs(props)),
              <.div(^.className := "tab-content",
                props.trainingHubLoc.modeStr match {
                  case "trainingMaterial" =>
                    TrainingMaterialComponent()
                  case "dropInBooking" =>
                    <.div(model.dropIns.render(dropIns => {
                      DropInComponent(dropIns)
                    }))

                }
              )
            )
        }
        ))
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def trainingTabs(props: Props): VdomTagOf[UList] = {
    def tabClass(mode: TrainingHubPageMode): String = if (props.trainingHubLoc.modeStr == mode.asString) activeClass else ""

    <.ul(^.className := "nav nav-tabs",
      <.li(^.className := tabClass(DropInBooking),
        props.router.link(props.trainingHubLoc.copy(modeStr = DropInBooking.asString))(
          ^.id := "dropInBooking", "Book a Drop-in Session", VdomAttr("data-toggle") := "tab"
        )
      ),
      <.li(^.className := tabClass(TrainingMaterial),
        props.router.link(props.trainingHubLoc.copy(modeStr = TrainingMaterial.asString))(
          ^.id := "trainingMaterial", "Training Material", VdomAttr("data-toggle") := "tab"
        )
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
