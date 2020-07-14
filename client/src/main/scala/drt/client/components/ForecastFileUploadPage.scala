package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.{ForecastFileUploadAction, ResetFileUpload}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.AirportConfig
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent, _}
import org.scalajs.dom
import org.scalajs.dom.html.Div
import org.scalajs.dom.raw.{FormData, HTMLFormElement}


case class FileUploadState(state: String, message: String)

case class FileUploadStateModel(fileUploadState: Pot[FileUploadState], airportConfig: Pot[AirportConfig])

object ForecastFileUploadPage {

  case class Props()

  val heading = <.h3("Forecast Feed File Upload")

  val upload: String => VdomTagOf[Div] = (portCode: String) =>
    <.div(^.className := "fileUpload",
      heading,
      <.br(),
      <.form(<.input(^.`type` := "file", ^.name := "filename"),
        <.br(),
        <.input(^.`type` := "button", ^.value := "Upload", ^.onClick ==> onSubmit(portCode)))
    )

  val uploadResult: String => VdomTagOf[Div] = (message: String) =>
    <.div(
      heading,
      <.br(),
      <.div(s"Upload status : $message"),
      <.br(),
      <.button(^.`type` := "button", "Upload another file", ^.onClick ==> onReset)
    )

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ForecastFileUpload")
    .render_P { _ =>
      val fileUploadStateRCP = SPACircuit.connect(m => FileUploadStateModel(m.fileUploadState, m.airportConfig))
      <.div(
        fileUploadStateRCP(fileUploadStateMP => {
          if (fileUploadStateMP().fileUploadState.isEmpty) {
            upload(fileUploadStateMP().airportConfig.head.portCode.iata)
          } else {
            <.div(fileUploadStateMP().fileUploadState.render(details => {
              details.state match {
                case "uploaded" | "error" => uploadResult(details.message)
                case _ => upload(fileUploadStateMP().airportConfig.head.portCode.iata)
              }
            })
            )
          }
        })
      )
    }.componentDidMount(_ => Callback {
    GoogleEventTracker.sendPageView(s"forecastFileUpload")
  }).build

  def apply(): VdomElement = component(Props())


  def onReset(e: ReactEventFromInput): Callback = {
    e.preventDefaultCB >> Callback {
      SPACircuit.dispatch(ResetFileUpload())
    }
  }

  def onSubmit(portCode: String)(e: ReactEventFromInput): Callback = {
    e.preventDefaultCB >> Callback {
      val tFormElement = e.target.parentNode.domCast[HTMLFormElement]
      val tFormData: FormData = new dom.FormData(tFormElement)

      SPACircuit.dispatch(ForecastFileUploadAction(portCode, tFormData))
    }
  }

}
