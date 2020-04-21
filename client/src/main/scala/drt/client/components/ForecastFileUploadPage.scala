package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.{ForecastFileUploadAction, ResetFileUpload}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent, _}
import org.scalajs.dom
import org.scalajs.dom.raw.{FormData, HTMLFormElement}


case class FileUploadState(state: String, message: String)

case class FileUploadStateModel(fileUploadState: Pot[FileUploadState])

object ForecastFileUploadPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("ForecastFileUpload")
    .render_P(_ =>
      <.div(^.className := "fileUpload",
        <.h3("Forecast Feed File Upload"),
        <.form(<.input(^.`type` := "file", ^.name := "filename"),
          <.input(^.`type` := "button", ^.value := "send", ^.onClick ==> onSubmit)),
            <.br(),
        ResultFileUploadComponent(),
      )
    )
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"forecastFileUpload")
    })
    .build


  object ResultFileUploadComponent {

    case class Props()

    val resultComponent: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ResultFileUpload")
      .render_P { _ =>
        val fileUploadStateRCP = SPACircuit.connect(m => FileUploadStateModel(m.fileUploadState))
        <.div(
          fileUploadStateRCP(fileUploadStateMP => {
            <.div(fileUploadStateMP().fileUploadState.render(details => {
              if (details.state == "uploaded") {
                <.div(
                  <.div(s"Upload status : ${details.message}"),
                  <.button(^.`type` := "button", "reset", ^.onClick ==> onReset)
                )
              } else if (details.state == "error") {
                <.div(
                  <.div(s"Upload status : ${details.message}"),
                  <.button(^.`type` := "button", "reset", ^.onClick ==> onReset)
                )
              } else {
                <.div()
              }
            })
            )
          })
        )
      }.build

    def apply(): VdomElement = resultComponent(Props())

  }

  def apply(): VdomElement = component(Props())


  def onReset(e: ReactEventFromInput): Callback = {
    e.preventDefaultCB >> Callback {
      SPACircuit.dispatch(ResetFileUpload())
    }
  }

  def onSubmit(e: ReactEventFromInput): Callback = {
    e.preventDefaultCB >> Callback {
      val tFormElement = e.target.parentNode.domCast[HTMLFormElement]
      val tFormData: FormData = new dom.FormData(tFormElement)

      SPACircuit.dispatch(ForecastFileUploadAction("lhr", tFormData))
    }
  }

}
