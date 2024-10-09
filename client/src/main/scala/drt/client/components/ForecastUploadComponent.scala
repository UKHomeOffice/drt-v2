package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.{FileUploadInProgress, ForecastFileUploadAction, ResetFileUpload}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent, _}
import org.scalajs.dom
import org.scalajs.dom.File
import org.scalajs.dom.html.{Div, Heading}
import uk.gov.homeoffice.drt.ports.AirportConfig


case class FileUploadState(state: String, message: String)

case class FileUploadStateModel(fileUploadState: Pot[FileUploadState], airportConfig: Pot[AirportConfig])

object ForecastUploadComponent {

  case class Props(airportConfigPot: Pot[AirportConfig])

  val heading: VdomTagOf[Heading] = <.h3("Forecast Feed File Upload")

  val upload: String => VdomTagOf[Div] = (portCode: String) =>
    <.div(^.className := "fileUpload",
      heading,
      <.br(),
      <.form(<.input(^.`type` := "file", ^.id := "forecast-file"),
        <.br(),
        <.input(^.`type` := "button", ^.value := "Upload", ^.onClick ==> onSubmit(portCode))
      )
    )

  private val uploadingInProgress: String => VdomTagOf[Div] = { message =>
    <.div(
      heading,
      <.br(),
      <.div(s"Upload status : $message"),
      <.br(),
      <.span("Uploading ....."),
    )
  }

  private val uploadResult: String => VdomTagOf[Div] = (message: String) =>
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
      fileUploadStateRCP(fileUploadStateMP => {
        <.div(
          fileUploadStateMP().airportConfig.renderReady(airportConfig =>
            if (fileUploadStateMP().fileUploadState.isEmpty) {
              upload(airportConfig.portCode.iata)
            } else {
              <.div(fileUploadStateMP().fileUploadState.render(details => {
                details.state match {
                  case "uploaded" | "error" => uploadResult(details.message)
                  case "uploadInProgress" => uploadingInProgress(details.message)
                  case _ => upload(airportConfig.portCode.iata)
                }
              }))
            })
        )
      }
      )
    }
    .build

  def apply(airportConfigPot: Pot[AirportConfig]): VdomElement = component(Props(airportConfigPot))


  private def onReset(e: ReactEventFromInput): Callback = {
    e.preventDefaultCB >> Callback {
      SPACircuit.dispatch(ResetFileUpload())
    }
  }

  private def onSubmit(portCode: String)(e: ReactEventFromInput): Callback = {
    SPACircuit.dispatch(FileUploadInProgress())
    e.preventDefaultCB >> Callback {
      val file: File = dom.document
        .getElementById("forecast-file")
        .asInstanceOf[dom.HTMLInputElement]
        .files(0)
      SPACircuit.dispatch(ForecastFileUploadAction(portCode, file))
    }
  }
}
