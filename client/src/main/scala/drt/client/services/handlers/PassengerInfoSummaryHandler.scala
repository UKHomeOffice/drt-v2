package drt.client.services.handlers

import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.services.DrtApi
import drt.client.services.JSDateConversions.SDate
import drt.shared.api.FlightManifestSummary
import drt.shared.{ArrivalKey, PortState}
import uk.gov.homeoffice.drt.time.UtcDate
import upickle.default.read

import scala.collection.immutable.Map
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

//class PassengerInfoSummaryHandler[M](modelRW: ModelRW[M, Pot[Map[ArrivalKey, FlightManifestSummary]]]) extends LoggingActionHandler(modelRW) {
//  override def handle: PartialFunction[Any, ActionResult[M]] = {
//    case GetPassengerInfoSummary(arrivalKey) =>
//      effectOnly(Effect(DrtApi.get(s"manifest/${arrivalKey.origin.iata}/${arrivalKey.voyageNumber.numeric}/${arrivalKey.scheduled}")
//        .map { response =>
//          val passengerInfo = read[FlightManifestSummary](response.responseText)
//          SetPassengerInfoSummary(arrivalKey, passengerInfo)
//        }))
//
//    case SetPassengerInfoSummary(arrivalKey, passengerInfo) =>
//      val existing = value.getOrElse(Map())
//      updated(Ready(existing.updated(arrivalKey, passengerInfo)))
//  }
//}
