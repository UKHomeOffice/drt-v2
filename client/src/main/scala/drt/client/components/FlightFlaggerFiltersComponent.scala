package drt.client.components

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.VdomElement

@js.native
trait FormState extends js.Object {
  var showTransitPaxNumber: Boolean
  var showNumberOfVisaNationals: Boolean
  var requireAllSelected: Boolean
  var flightNumber: String
}

object FormState {
  def apply(
             showTransitPaxNumber: Boolean,
             showNumberOfVisaNationals: Boolean,
             requireAllSelected: Boolean,
             flightNumber: String
           ): FormState = {
    val p = (new js.Object).asInstanceOf[FormState]
    p.showTransitPaxNumber = showTransitPaxNumber
    p.showNumberOfVisaNationals = showNumberOfVisaNationals
    p.requireAllSelected = requireAllSelected
    p.flightNumber = flightNumber
    p
  }
}

@js.native
trait FlightArrival extends js.Object {
  var highlights: js.UndefOr[js.Array[String]]
  var flight: String
  var origin: String
  var country: String
  var gate: js.UndefOr[String]
  var status: js.UndefOr[String]
  var scheduled: js.UndefOr[String]
  var expected: js.UndefOr[String]
  var expPcp: js.UndefOr[String]
  var expPcpPax: js.UndefOr[ExpPcpPax]
  var paxCounts: js.UndefOr[PaxCounts]
}

object FlightArrival {
  def apply(
             highlights: js.UndefOr[js.Array[String]],
             flight: String,
             origin: String,
             country: String,
             gate: js.UndefOr[String],
             status: js.UndefOr[String],
             scheduled: js.UndefOr[String],
             expected: js.UndefOr[String],
             expPcp: js.UndefOr[String],
             expPcpPax: js.UndefOr[ExpPcpPax],
             paxCounts: js.UndefOr[PaxCounts]
           ): FlightArrival = {
    val p = (new js.Object).asInstanceOf[FlightArrival]
    p.highlights = highlights
    p.flight = flight
    p.origin = origin
    p.country = country
    p.gate = gate
    p.status = status
    p.scheduled = scheduled
    p.expected = expected
    p.expPcp = expPcp
    p.expPcpPax = expPcpPax
    p.paxCounts = paxCounts
    p
  }
}

@js.native
trait ExpPcpPax extends js.Object {
  var confidence: js.UndefOr[String]
  var count: js.UndefOr[Int]
}

object ExpPcpPax {
  def apply(
             confidence: js.UndefOr[String],
             count: js.UndefOr[Int]
           ): ExpPcpPax = {
    val p = (new js.Object).asInstanceOf[ExpPcpPax]
    p.confidence = confidence
    p.count = count
    p
  }
}

@js.native
trait PaxCounts extends js.Object {
  var confidence: js.UndefOr[String]
  var eGate: js.UndefOr[Int]
  var eea: js.UndefOr[Int]
  var nonEea: js.UndefOr[Int]
}

object PaxCounts {
  def apply(
             confidence: js.UndefOr[String],
             eGate: js.UndefOr[Int],
             eea: js.UndefOr[Int],
             nonEea: js.UndefOr[Int]
           ): PaxCounts = {
    val p = (new js.Object).asInstanceOf[PaxCounts]
    p.confidence = confidence
    p.eGate = eGate
    p.eea = eea
    p.nonEea = nonEea
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
}

object SearchFilterPayload {
  def apply(
             showTransitPaxNumber: Boolean,
             showNumberOfVisaNationals: Boolean,
             selectedAgeGroups: js.Array[String],
             selectedNationalities: js.Array[String],
             flightNumber: String
           ): SearchFilterPayload = {
    val p = (new js.Object).asInstanceOf[SearchFilterPayload]
    p.showTransitPaxNumber = showTransitPaxNumber
    p.showNumberOfVisaNationals = showNumberOfVisaNationals
    p.selectedAgeGroups = selectedAgeGroups
    p.selectedNationalities = selectedNationalities
    p.flightNumber = flightNumber
    p
  }
}

@js.native
trait FlightFlaggerProps extends js.Object {
  var nationalities: js.Array[String]
  var ageGroups: js.Array[String]
  var submitCallback: js.Function1[SearchFilterPayload, Unit]
  var flights: js.Array[FlightArrival]
  var isLoading: Boolean
}

object FlightFlaggerProps {
  def apply(
             nationalities: js.Array[String],
             ageGroups: js.Array[String],
             submitCallback: js.Function1[SearchFilterPayload, Unit],
             flights: js.Array[FlightArrival],
             isLoading: Boolean
           ): FlightFlaggerProps = {
    val p = (new js.Object).asInstanceOf[FlightFlaggerProps]
    p.nationalities = nationalities
    p.ageGroups = ageGroups
    p.submitCallback = submitCallback
    p.flights = flights
    p.isLoading = isLoading
    p
  }
}

@js.native
@JSImport("@drt/drt-react-components", "FlightFlagger")
object FlightFlagger extends js.Object


object FlightFlaggerComponent {

  val component = JsComponent[FlightFlaggerProps, Children.None, Null](FlightFlagger)

  def apply(props: FlightFlaggerProps): VdomElement = component(props)
}

@js.native
trait StatusTagProps extends js.Object {
  var `type`: String
  var text: String
}

object StatusTagProps {
  def apply(`type`: String, text: String): StatusTagProps = {
    val p = (new js.Object).asInstanceOf[StatusTagProps]
    p.`type` = `type`
    p.text = text
    p
  }
}

object StatusTag {

  @js.native
  @JSImport("@drt/drt-react-components", "StatusTag")
  object RawComponent extends js.Object

  val Component = JsFnComponent[StatusTagProps, Children.None](RawComponent)

  def apply(`type`: String, text: String) = Component(StatusTagProps(`type`, text))
}
