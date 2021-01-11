package drt.client.services.handlers

import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.api.PassengerInfoSummary
import drt.shared.dates.UtcDate
import drt.shared.{ArrivalKey, PortState}
import upickle.default.read

import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PassengerInfoSummaryHandler[M](
                                      portStatePot: ModelR[M, Pot[PortState]],
                                      modelRW: ModelRW[M, Pot[Map[UtcDate, Map[ArrivalKey, PassengerInfoSummary]]]]
                                    ) extends LoggingActionHandler(modelRW) {

  def daysToRequestManifestsFor: Set[UtcDate] = portStatePot
    .zoom(_.map(_.flights.keys.map(k => SDate(k.scheduled).toUtcDate).toSet)).value.getOrElse(Set())

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPassengerInfoForFlights =>
      val flightDays = daysToRequestManifestsFor.map(day => Effect(Future(GetPassengerInfoSummary(day)))).toList

      val firstFlightDay: Effect = flightDays.head
      val remainingFlightDays = flightDays.tail

      val effects = remainingFlightDays.foldLeft(firstFlightDay)((acc, ef) => acc + ef)

      effectOnly(effects)

    case GetPassengerInfoSummary(utcDate) =>
      effectOnly(Effect(DrtApi.get(s"manifest/${utcDate.toISOString}/summary")
        .map { response =>

          val passengerInfo = read[Seq[PassengerInfoSummary]](response.responseText)
          SetPassengerInfoSummary(utcDate, passengerInfo)
        }))

    case SetPassengerInfoSummary(utcDate, passengerInfo) =>
      val updates: Map[ArrivalKey, PassengerInfoSummary] = passengerInfo.map(pi => (pi.arrivalKey) -> pi).toMap
      val existing = value.getOrElse(Map())

      updated(Ready(existing ++ Map(utcDate -> updates)))
  }
}
