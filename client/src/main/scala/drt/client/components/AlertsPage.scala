package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{DeleteAllAlerts, SaveAlert}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Alert
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

object AlertsPage {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class State(title: Option[String] = None, message: Option[String] = None, expiryDateTime: Option[MillisSinceEpoch] = None)

  val component = ScalaComponent.builder[Unit]("Alerts")
    .initialState(State())
    .renderS((scope, state) => {

      val modelRCP = SPACircuit.connect(m => m.alerts)

      def deleteAllAlerts = (_: ReactEventFromInput) => {
        SPACircuit.dispatch(DeleteAllAlerts)
        scope.setState(State())
      }

      def addAlert() = (_: ReactEventFromInput) => {
        for {
          title <- state.title
          message <- state.message
          expiryDateTime <- state.expiryDateTime
        } yield {
          SPACircuit.dispatch(SaveAlert(Alert(title, message, expiryDateTime, SDate.now().millisSinceEpoch)))
        }
        scope.setState(State())
      }

      def setTitle(title: String) = scope.modState(state => state.copy(title = Option(title)))

      def setMessage(message: String) = scope.modState(state => state.copy(message = Option(message)))

      def setExpiryDateTime(expiryDateTime: String) = {
        SDate.stringToSDateLikeOption(expiryDateTime).map { date =>
          scope.modState(state => state.copy(expiryDateTime = Option(date.millisSinceEpoch)))
        }.getOrElse(Callback(Unit))

      }

      def isValid: Boolean = state.expiryDateTime.exists(dateTime => dateTime > SDate.now().millisSinceEpoch) && state.title.exists(s => s.trim != "") && state.message.exists(s => s.trim != "")

      <.span(
        <.h2("Add an alert"),
        <.div(^.`class` := "row", ^.height := "30px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-title", "Title", ^.`class` := "col-md-3"),
          <.input.text(^.id := "alert-title", ^.placeholder := "Title", ^.`class` := "col-md-3", ^.value := state.title.getOrElse(""), ^.onChange ==> ((e: ReactEventFromInput) => setTitle(e.target.value))),
          <.div(^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-message", "Message", ^.`class` := "col-md-3"),
          <.textarea(^.id := "alert-message", ^.placeholder := "Message", ^.rows := 10, ^.`class` := "col-md-3", ^.value := state.message.getOrElse(""), ^.onChange ==> ((e: ReactEventFromInput) => setMessage(e.target.value))),
          <.div(^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-date-time", "Expiry date", ^.`class` := "col-md-3"),
          <.input.datetimeLocal(^.id := "alert-date-time", ^.`class` := "col-md-3", ^.onChange ==> ((e: ReactEventFromInput) => setExpiryDateTime(e.target.value))),
          <.div(^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.div(^.`class` := "col-md-3"),
          <.button("Add alert", ^.`class` := "col-md-3 btn bg-success", ^.disabled := (!isValid), ^.onClick ==> addAlert()),
          <.div(^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          modelRCP { modelMP: ModelProxy[Pot[Seq[Alert]]] =>
            val alertsPot = modelMP()
            <.div(
              alertsPot.render((alerts: Seq[Alert]) => {
                <.div(
                  <.button("Delete all alerts", ^.`class` := "col-md-3 btn bg-danger", ^.onClick ==> deleteAllAlerts),
                  <.div(^.`class` := "col-md-9")
                )
              }),
              alertsPot.renderEmpty(<.div(^.id := "no-alerts-to-delete"))
            )
          }
        )
      )
    })
    .build

  def apply(): VdomElement = component()
}
