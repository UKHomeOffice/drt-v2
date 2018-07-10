package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetAirportInfos, RetryActionAfter, UpdateAirportInfo, UpdateAirportInfos}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.{AirportInfo, Api}

import scala.collection.immutable.Map
import scala.concurrent.Future

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportInfos(codes) =>
      log.info(s"Calling airportInfosByAirportCodes")
      val stringToObject: Map[String, Pot[AirportInfo]] = value ++ Map("BHX" -> mkPending, "EDI" -> mkPending)
      updated(stringToObject, Effect(AjaxClient[Api].airportInfosByAirportCodes(codes).call().map(UpdateAirportInfos)
        .recoverWith {
          case _ =>
            log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetAirportInfos(codes), PollDelay.recoveryDelay))
        }
      ))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + (code -> Ready(airportInfo))
      updated(newValue)
  }
}
