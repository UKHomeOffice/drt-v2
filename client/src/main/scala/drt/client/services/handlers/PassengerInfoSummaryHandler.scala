package drt.client.services.handlers

import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.services.DrtApi
import drt.client.services.JSDateConversions.SDate
import drt.shared.api.PassengerInfoSummary
import drt.shared.{ArrivalKey, PortState}
import uk.gov.homeoffice.drt.time.UtcDate
import upickle.default.read

import scala.collection.immutable.Map
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PassengerInfoSummaryHandler[M](portStatePot: ModelR[M, Pot[PortState]],
                                     modelRW: ModelRW[M, Pot[Map[ArrivalKey, PassengerInfoSummary]]]
                                    ) extends LoggingActionHandler(modelRW) {

  def daysToRequestManifestsFor: Set[UtcDate] = portStatePot
    .zoom(_.map(_.flights.keys.map(k => SDate(k.scheduled).toUtcDate).toSet)).value.getOrElse(Set())

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPassengerInfoSummary(arrivalKey) =>
      effectOnly(Effect(DrtApi.get(s"manifest/${arrivalKey.origin.iata}/${arrivalKey.voyageNumber.numeric}/${arrivalKey.scheduled}")
        .map { response =>
          val passengerInfo = read[PassengerInfoSummary](response.responseText)
          SetPassengerInfoSummary(arrivalKey, passengerInfo)
        }))

    case SetPassengerInfoSummary(arrivalKey, passengerInfo) =>
      val existing = value.getOrElse(Map())
      updated(Ready(existing.updated(arrivalKey, passengerInfo)))
  }
}