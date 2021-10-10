package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.mvc.{Action, AnyContent}
import spray.json.{DefaultJsonProtocol, JsArray, JsBoolean, JsNumber, JsObject, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.auth.Roles.EgateBanksEdit
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.PortCode
import upickle.default._

import scala.concurrent.Future


trait WithEgateBanks {
  self: Application =>

  def getEgateBanksUpdates: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: EgateBanksJsonFormats.egateBanksUpdatesJsonFormat.type = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
      ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[EgateBanksUpdates].map(r => Ok(r.toJson.compactPrint))
    }

  def getEgateBanksUpdatesLegacy: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: EgateBanksJsonFormats.egateBanksUpdatesJsonFormat.type = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
      ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[EgateBanksUpdates].map(r => Ok(write(r)))
    }

  def updateEgateBanksUpdates(): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            import spray.json._

            implicit val rdu = EgateBanksJsonFormats.egateBanksUpdateJsonFormat
            implicit val rdus = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
            implicit val srdu = EgateBanksJsonFormats.setEgateBanksUpdatesJsonFormat

            val setUpdate = text.parseJson.convertTo[SetEgateBanksUpdate]

            ctrl.egateBanksUpdatesActor.ask(setUpdate).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }
    }
  }

  def deleteEgateBanksUpdates(effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      ctrl.egateBanksUpdatesActor.ask(DeleteEgateBanksUpdates(effectiveFrom)).map(_ => Accepted)
    }
  }
}

object EgateBanksJsonFormats extends DefaultJsonProtocol {
    implicit object egateBanksUpdateJsonFormat extends RootJsonFormat[EgateBanksUpdate] {
      override def write(obj: EgateBanksUpdate): JsValue = {
        val banks = obj.banks.map { bank =>
          JsArray(bank.gates.map(_.toJson).toVector)
        }
        JsObject(Map(
          "effectiveFrom" -> JsNumber(obj.effectiveFrom),
          "banks" -> JsArray(banks.toVector),
        ))
      }

      override def read(json: JsValue): EgateBanksUpdate = json match {
        case JsObject(fields) =>
          val maybeStuff = for {
            effectiveFrom <- fields.get("effectiveFrom").collect { case JsNumber(value) => value.toLong }
            banks <- fields.get("banks").collect {
              case JsArray(jsBanks) =>
                jsBanks.collect {
                  case JsArray(bank) =>
                    EgateBank(bank.map {
                      case JsBoolean(gateIsOpen) => gateIsOpen
                    }.toIndexedSeq)
                  case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
                }
              case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
            }
          } yield EgateBanksUpdate(effectiveFrom, banks)
          maybeStuff.getOrElse(throw new Exception("Failed to deserialise EgateBanksUpdate json"))
      }
    }

  implicit object egateBanksUpdatesJsonFormat extends RootJsonFormat[EgateBanksUpdates] {
    override def write(obj: EgateBanksUpdates): JsValue = JsArray(obj.updates.map(_.toJson).toVector)

    override def read(json: JsValue): EgateBanksUpdates = json match {
      case JsObject(fields) => fields.get("updates") match {
        case Some(JsObject(updates)) =>
          val egateBanksUpdates = updates.map {
            case (effectiveFrom, egateBanksUpdateJson) => egateBanksUpdateJson.convertTo[EgateBanksUpdate]
          }
          EgateBanksUpdates(egateBanksUpdates.toList)
      }
    }
  }

  implicit val setEgateBanksUpdatesJsonFormat: RootJsonFormat[SetEgateBanksUpdate] = jsonFormat2(SetEgateBanksUpdate.apply)

  implicit val portCodeJsonFormat: RootJsonFormat[PortCode] = jsonFormat(PortCode.apply, "iata")
}
