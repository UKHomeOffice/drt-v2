package drt.client.components

import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.client.spa.TrainingHubPageMode
import drt.client.spa.TrainingHubPageModes.{SeminarBooking, TrainingMaterial}
import drt.shared.Seminar
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.AirportConfig

object TrainingHubComponent {

  val log: Logger = LoggerFactory.getLogger("TrainingHubComponent")

  case class Props(trainingHubLoc: TrainingHubLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.trainingHubLoc == b.trainingHubLoc)

  private case class TrainingModel(airportConfig: Pot[AirportConfig],
                                   loggedInUserPot: Pot[LoggedInUser],
                                   seminars: Pot[Seq[Seminar]]
                                  ) extends UseValueEq

  private val activeClass = "active"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TrainingHubComponent")
    .render_P { props =>
      val modelRCP = SPACircuit.connect(model => TrainingModel(
        airportConfig = model.airportConfig,
        loggedInUserPot = model.loggedInUserPot,
        seminars = model.seminars
      ))

      <.div(
        modelRCP(modelMP => {
          val model: TrainingModel = modelMP()
          for {
            airportConfig <- model.airportConfig
            loggedInUser <- model.loggedInUserPot
          } yield {
            <.div(
              <.div(^.className := "terminal-nav-wrapper", trainingTabs(props)),
              <.div(^.className := "tab-content",
                props.trainingHubLoc.modeStr match {
                  case "trainingMaterial" =>
                    TrainingMaterialComponent()
                  case "seminarBooking" =>
                    <.div(model.seminars.render(seminars => {
                      SeminarComponent(loggedInUser.email, seminars)
                    }))

                }
              )
            )
          }
        }.getOrElse(LoadingOverlay())
        ))
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def trainingTabs(props: Props): VdomTagOf[UList] = {
    val trainingName = props.trainingHubLoc.modeStr

    def tabClass(mode: TrainingHubPageMode): String = if (props.trainingHubLoc.modeStr == mode.asString) activeClass else ""

    <.ul(^.className := "nav nav-tabs",
      <.li(^.className := tabClass(TrainingMaterial),
        <.a(^.id := "trainingMaterial", "Training Material", VdomAttr("data-toggle") := "tab"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          GoogleEventTracker.sendEvent(trainingName, "click", "Training Material")
          props.router.set(props.trainingHubLoc.copy(
            modeStr = TrainingMaterial.asString
          ))
        }),
      <.li(^.className := tabClass(SeminarBooking),
        <.a(^.id := "seminarBooking", "Seminar Booking", VdomAttr("data-toggle") := "tab"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          GoogleEventTracker.sendEvent(trainingName, "click", "Seminar Booking")
          props.router.set(props.trainingHubLoc.copy(
            modeStr = SeminarBooking.asString
          ))
        }
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)

}
