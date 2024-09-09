package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.AirportInfo
import uk.gov.homeoffice.drt.ports.PortCode
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AirportCountryHandler[M](modelRW: ModelRW[M, Map[PortCode, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportInfos(codes) =>
      effectOnly(Effect(DrtApi.get(s"airport-info?portCode=${codes.map(_.iata).mkString(",")}")
        .map { response =>
          val codeToInfo = read[Map[PortCode, AirportInfo]](response.responseText)
          UpdateAirportInfos(codeToInfo)
        }
        .recoverWith {
          case _ => Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
        }))

    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      val newInfos = value ++ infosReady
      value match {
        case existing if existing != newInfos =>
          updated(newInfos)
        case _ =>
          noChange

      }
  }
}
