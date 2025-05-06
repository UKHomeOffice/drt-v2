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

  val heading: String => VdomTagOf[Heading] = header => <.h1(s"Forecast Feed File Upload $header")

  val upload: (String, String) => VdomTagOf[Div] = (portCode: String, header: String) =>
    <.div(^.className := "fileUpload",
      heading(header),
      <.br(),
      <.form(<.input(^.`type` := "file", ^.id := "forecast-file"),
        <.br(),
        <.input(^.`type` := "button", ^.value := "Upload", ^.onClick ==> onSubmit(portCode))
      )
    )

  private val uploadingInProgress: (String, String) => VdomTagOf[Div] = { (message, header) =>
    <.div(
      heading(header),
      <.br(),
      <.div(s"Upload status : $message"),
      <.br(),
      <.span("Uploading ....."),
    )
  }

  private val uploadResult: (String, String) => VdomTagOf[Div] = (message: String, header: String) =>
    <.div(
      heading(header),
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
          fileUploadStateMP().airportConfig.renderReady { airportConfig =>
            val headerString = s"${airportConfig.portCode.iata} (${airportConfig.portName})"
            if (fileUploadStateMP().fileUploadState.isEmpty) {
              upload(airportConfig.portCode.iata, headerString)
            } else {
              <.div(fileUploadStateMP().fileUploadState.render(details => {
                details.state match {
                  case "uploaded" | "error" => uploadResult(details.message, headerString)
                  case "uploadInProgress" => uploadingInProgress(details.message, headerString)
                  case _ => upload(airportConfig.portCode.iata, headerString)
                }
              }))
            }
          }
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
