package drt.client.components

import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IAnalyticsEvent extends js.Object {
  var category: String
  var action: String
  var label: String
}

object IAnalyticsEvent {
  def apply(category: String, action: String, label: String): IAnalyticsEvent = {
    val p = (new js.Object).asInstanceOf[IAnalyticsEvent]
    p.category = category
    p.action = action
    p.label = label
    p
  }
}

@js.native
trait Country extends js.Object {
  var name: String
  var code: String
}

object CountryJS {
  def apply(name: String, code: String): Country = {
    val p = (new js.Object).asInstanceOf[Country]
    p.name = name
    p.code = code
    p
  }
}

@js.native
trait SearchFilterPayload extends js.Object {
  var showTransitPaxNumber: Boolean
  var showNumberOfVisaNationals: Boolean
  var selectedAgeGroups: js.Array[String]
  var selectedNationalities: js.Array[Country]
  var flightNumber: String
  var requireAllSelected: Boolean
}

object SearchFilterPayload {
  def apply(
             showTransitPaxNumber: Boolean,
             showNumberOfVisaNationals: Boolean,
             selectedAgeGroups: js.Array[String],
             selectedNationalities: js.Array[Country],
             flightNumber: String,
             requireAllSelected: Boolean
           ): SearchFilterPayload = {
    val p = (new js.Object).asInstanceOf[SearchFilterPayload]
    p.showTransitPaxNumber = showTransitPaxNumber
    p.showNumberOfVisaNationals = showNumberOfVisaNationals
    p.selectedAgeGroups = selectedAgeGroups
    p.selectedNationalities = selectedNationalities
    p.flightNumber = flightNumber
    p.requireAllSelected = requireAllSelected
    p
  }
}


@js.native
trait FlightFlaggerFiltersProps extends js.Object {
  var port: String = js.native
  var terminal: String = js.native
  var nationalities: js.Array[Country] = js.native
  var ageGroups: js.Array[String] = js.native
  var submitCallback: js.Function1[js.Object, Unit] = js.native
  var showAllCallback: js.Function1[js.Object, Unit] = js.native
  var clearFiltersCallback: js.Function1[js.Object, Unit] = js.native
  var onChangeInput: js.Function1[String, Unit] = js.native
  var maybeInitialState: js.UndefOr[js.Dynamic] = js.native
  var sendEvent: js.Function1[IAnalyticsEvent, Unit] = js.native
}

object FlightFlaggerFiltersProps {
  def apply(
             port: String,
             terminal: String,
             nationalities: js.Array[Country],
             ageGroups: js.Array[String],
             submitCallback: js.Function1[js.Object, Unit],
             showAllCallback: js.Function1[js.Object, Unit],
             clearFiltersCallback: js.Function1[js.Object, Unit],
             onChangeInput: js.Function1[String, Unit],
             initialState: js.UndefOr[js.Dynamic],
             sendEvent: js.Function1[IAnalyticsEvent, Unit]
           ): FlightFlaggerFiltersProps = {
    val p = (new js.Object).asInstanceOf[FlightFlaggerFiltersProps]
    p.port = port
    p.terminal = terminal
    p.nationalities = nationalities
    p.ageGroups = ageGroups
    p.submitCallback = submitCallback
    p.showAllCallback = showAllCallback
    p.clearFiltersCallback = clearFiltersCallback
    p.onChangeInput = onChangeInput
    p.maybeInitialState = initialState
    p.sendEvent = sendEvent
    p
  }
}

object FlightFlaggerFilters {

  @js.native
  @JSImport("@drt/drt-react", "FlightFlaggerFilters")
  object RawComponent extends js.Object

  class Backend {
    def render(props: FlightFlaggerFiltersProps): VdomElement = {
      val component = JsFnComponent[FlightFlaggerFiltersProps, Children.None](RawComponent)
      component(props)
    }
  }

  val component: Component[FlightFlaggerFiltersProps, Unit, Backend, CtorType.Props] =
    ScalaComponent.builder[FlightFlaggerFiltersProps]("FlightFlaggerFilters")
      .renderBackend[Backend]
      .build

  def apply(
             port: String,
             terminal: String,
             nationalities: js.Array[Country],
             ageGroups: js.Array[String],
             submitCallback: js.Function1[js.Object, Unit],
             showAllCallback: js.Function1[js.Object, Unit],
             clearFiltersCallback: js.Function1[js.Object, Unit],
             onChangeInput: js.Function1[String, Unit],
             initialState: js.UndefOr[js.Dynamic],
             sendEvent: js.Function1[IAnalyticsEvent, Unit]
           ): VdomElement = {
    val props = FlightFlaggerFiltersProps(
      port,
      terminal,
      nationalities,
      ageGroups,
      submitCallback,
      showAllCallback,
      clearFiltersCallback,
      onChangeInput,
      initialState,
      sendEvent
    )
    component(props)
  }
}
