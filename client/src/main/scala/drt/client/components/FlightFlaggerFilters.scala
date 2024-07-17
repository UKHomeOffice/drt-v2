package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport


@js.native
trait AutocompleteOption extends js.Object {
  var title: String
}
object AutocompleteOption {
  def apply(title: String): AutocompleteOption = {
    val p = (new js.Object).asInstanceOf[AutocompleteOption]
    p.title = title
    p
  }
}

@js.native
trait SearchFilterPayload extends js.Object {
  var showTransitPaxNumber: Boolean
  var showNumberOfVisaNationals: Boolean
  var selectedAgeGroups: js.Array[String]
  var selectedNationalities: js.Array[String]
  var flightNumber: String
  var requireAllSelected: Boolean
}

object SearchFilterPayload {
  def apply(
             showTransitPaxNumber: Boolean,
             showNumberOfVisaNationals: Boolean,
             selectedAgeGroups: js.Array[String],
             selectedNationalities: js.Array[String],
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
  var nationalities: js.Array[String] = js.native
  var ageGroups: js.Array[String] = js.native
  var submitCallback: js.Function1[js.Object, Unit] = js.native
  var showAllCallback: js.Function1[js.Object, Unit] = js.native
  var onChangeInput: js.Function1[String, Unit] = js.native
  var initialState: js.UndefOr[js.Dynamic] = js.native
}

object FlightFlaggerFiltersProps {
  def apply(
             nationalities: js.Array[String],
             ageGroups: js.Array[String],
             submitCallback: js.Function1[js.Object, Unit],
             showAllCallback: js.Function1[js.Object, Unit],
             onChangeInput: js.Function1[String, Unit],
             initialState: js.UndefOr[js.Dynamic]
           ): FlightFlaggerFiltersProps = {
    val p = (new js.Object).asInstanceOf[FlightFlaggerFiltersProps]
    p.nationalities = nationalities
    p.ageGroups = ageGroups
    p.submitCallback = submitCallback
    p.showAllCallback = showAllCallback
    p.onChangeInput = onChangeInput
    p.initialState = initialState
    p
  }
}

object FlightFlaggerFilters {

  @js.native
  @JSImport("@drt/drt-react", "FlightFlaggerFilters")
  object RawComponent extends js.Object

  val component = JsFnComponent[FlightFlaggerFiltersProps, Children.None](RawComponent)

  def apply(
             nationalities: js.Array[String],
             ageGroups: js.Array[String],
             submitCallback: js.Function1[js.Object, Unit],
             showAllCallback: js.Function1[js.Object, Unit],
             onChangeInput: js.Function1[String, Unit],
             initialState: js.UndefOr[js.Dynamic]
           ): VdomElement = {
    val props = FlightFlaggerFiltersProps(
      nationalities,
      ageGroups,
      submitCallback,
      showAllCallback,
      onChangeInput,
      initialState
    )
    component(props)
  }
}
