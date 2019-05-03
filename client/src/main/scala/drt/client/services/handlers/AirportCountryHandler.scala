package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.SPAMain
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.PollDelay
import drt.shared.AirportInfo
import org.scalajs.dom
import upickle.default.read

import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AirportCountryHandler[M](timeProvider: () => Long, modelRW: ModelRW[M, Map[String, Pot[AirportInfo]]]) extends LoggingActionHandler(modelRW) {
  def mkPending = Pending(timeProvider())

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportInfos(codes) =>

      val url = SPAMain.absoluteUrl(s"airport-info?portCode=${codes.mkString(",")}")

      val eventualAction: Future[UpdateAirportInfos] = dom.ext.Ajax.get(url = url).map(r => {

        val aiportInfos = read[Map[String,AirportInfo]]({
          log.info(s"respone: ${r.responseText}")
          r.responseText
        })
        log.info(s"Airport infos is: $aiportInfos")
        UpdateAirportInfos(aiportInfos)
      })
      effectOnly(Effect(eventualAction.recoverWith {
        case _ =>
          Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
      }))
    case UpdateAirportInfos(infos) =>
      val infosReady = infos.map(kv => (kv._1, Ready(kv._2)))
      updated(value ++ infosReady)
    case UpdateAirportInfo(code, Some(airportInfo)) =>
      val newValue = value + (code -> Ready(airportInfo))
      updated(newValue)
  }
}
