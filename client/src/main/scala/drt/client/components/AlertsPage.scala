package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{DeleteAllAlerts, SaveAlert}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.Alert
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.vdom.html_<^.{^, _}
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom

object AlertsPage {

  case class State(title: Option[String] = None, message: Option[String] = None, alertClass: Option[String] = None, expiryDateTime: Option[MillisSinceEpoch] = None, expiryDateTimeString: String = "")

  val defaultAlertClass = "notice"
  val defaultExpiryHours = 24

  def defaultExpiryMillis(): MillisSinceEpoch = SDate.now().addHours(defaultExpiryHours).millisSinceEpoch

  def defaultState(): State = State(alertClass = Option(defaultAlertClass), expiryDateTime = Option(defaultExpiryMillis()))

  val component = ScalaComponent.builder[Unit]("Alerts")
    .initialState(defaultState())
    .renderS((scope, state) => {

      val modelRCP = SPACircuit.connect(_.alerts)

      def deleteAllAlerts = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent("alerts", "Delete All Alerts", "")
        SPACircuit.dispatch(DeleteAllAlerts)
        scope.setState(defaultState())
      }

      def removeValidation(field: String): Unit = {
        <.span().renderIntoDOM(dom.document.getElementById(s"$field-error"))
      }

      def addValidation(field: String, message: String) = {
        val div = <.div(message, ^.`class` := "alert alert-danger")
        div.renderIntoDOM(dom.document.getElementById(s"$field-error"))
      }

      def doAddAlert(): Callback = {
        removeValidation("message")
        removeValidation("expiry")

        GoogleEventTracker.sendEvent("alerts", "Add Alert", alertFromState.toString)
        SPACircuit.dispatch(SaveAlert(alertFromState))

        scope.setState(defaultState())
      }

      def alertFromState: Alert = {
        val maybeAlert = for {
          message <- state.message
          alertClass <- state.alertClass
          expiryDateTime <- state.expiryDateTime
        } yield Alert(state.title.getOrElse(""), message, alertClass, expiryDateTime, SDate.now().millisSinceEpoch)

        maybeAlert.getOrElse(Alert.empty)
      }

      def validationMessages: Callback = {
        if (!state.message.exists(s => s.trim != "")) {
          addValidation("message", "Message needs a value")
        }
        scope.forceUpdate
      }

      def addAlert(): ReactEventFromInput => Callback = (_: ReactEventFromInput) => if (isValid) doAddAlert() else validationMessages

      def setTitle(title: String) = scope.modState(state => {
        state.copy(title = Option(title))
      })

      def setAlertClass(alertClass: String) = scope.modState(state => {
        state.copy(alertClass = Option(alertClass))
      })

      def setMessage(message: String) = scope.modState(state => {
        removeValidation("message")
        state.copy(message = Option(message))
      })

      def setExpiryDateTimeFromHours(hours: String) =
        scope.modState(state => state.copy(expiryDateTime = Option(SDate.now().addHours(hours.toInt).millisSinceEpoch)))

      def isValid: Boolean = state.message.exists(s => s.trim != "")

      <.span(
        <.h2("Add an alert"),
        <.div(^.`class` := "row", ^.height := "30px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-title", "Title", ^.`class` := "col-md-3"),
          <.input.text(^.id := "alert-title", ^.placeholder := "Title", ^.`class` := "col-md-3", ^.value := state.title.getOrElse(""), ^.onChange ==> ((e: ReactEventFromInput) => setTitle(e.target.value))),
          <.div(^.id := "title-error", ^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-alertClass", "Alert Type", ^.`class` := "col-md-3"),
          <.select(^.id := "alert-alertClass", ^.defaultValue := defaultAlertClass, List(<.option(^.name := "notice", "notice"), <.option(^.name := "warning", "warning")).toTagMod, ^.onChange ==> ((e: ReactEventFromInput) => setAlertClass(e.target.value)), ^.`class` := "col-md-3"),
          <.div(^.id := "alertClass-error", ^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-message", "Message", ^.`class` := "col-md-3"),
          <.textarea(^.id := "alert-message", ^.placeholder := "Message", ^.rows := 10, ^.`class` := "col-md-3", ^.value := state.message.getOrElse(""), ^.onChange ==> ((e: ReactEventFromInput) => setMessage(e.target.value))),
          <.div(^.id := "message-error", ^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.label(^.`for` := "alert-date-time", "Expires after", ^.`class` := "col-md-3"),
          <.select(^.id := "alert-date-time", ^.default := defaultExpiryHours, ^.defaultValue := defaultExpiryHours, (12 to 72 by 12).map(h => <.option(^.value := h, s"$h hours")).toTagMod, ^.onChange ==> ((e: ReactEventFromInput) => setExpiryDateTimeFromHours(e.target.value)), ^.`class` := "col-md-3"),
          <.div(^.id := "expiry-error", ^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          <.div(^.`class` := "col-md-3"),
          <.button("Add alert", ^.`class` := "col-md-3 btn btn-success", ^.onClick ==> addAlert()),
          <.div(^.`class` := "col-md-6")
        ),
        <.div(^.`class` := "row", ^.height := "10px"),
        <.div(^.`class` := "row",
          modelRCP { modelMP: ModelProxy[Pot[List[Alert]]] =>
            val alertsPot = modelMP()
            <.div(
              alertsPot.render { _ =>
                <.div(
                  <.div(^.`class` := "col-md-3"),
                  <.div(
                    <.button("Delete all alerts", ^.`class` := "col-md-3 btn btn-danger", ^.onClick ==> deleteAllAlerts),
                    <.div(^.`class` := "col-md-6")
                  )
                )
              },
              alertsPot.renderEmpty(<.div(^.id := "no-alerts-to-delete"))
            )
          }
        ),
        <.div(
          <.h2("Preview"),
          <.p("The alert will display at the top of the page next to the Border Force logo"),
          AlertsComponent.renderAlert(List(alertFromState))
        )
      )
    })
    .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView("alerts")))
    .build

  def apply(): VdomElement = component()
}
