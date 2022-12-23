package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.mvc.{Action, AnyContent}
import spray.json.{DefaultJsonProtocol, JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.auth.Roles.EgateBanksEdit
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._

import scala.concurrent.Future


trait WithEgateBanks {
  self: Application =>

  def getEgateBanksUpdates: Action[AnyContent] =
    Action.async { _ =>
      implicit val banksUpdatesFormat: EgateBanksJsonFormats.egateBanksUpdatesJsonFormat.type = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
      implicit val portBanksUpdatesFormat: EgateBanksJsonFormats.portEgateBanksUpdatesJsonFormat.type = EgateBanksJsonFormats.portEgateBanksUpdatesJsonFormat
      ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates].map(r => Ok(r.toJson.compactPrint))
    }

  def getEgateBanksUpdatesLegacy: Action[AnyContent] =
    Action.async { _ =>
      implicit val rluFormat: EgateBanksJsonFormats.egateBanksUpdatesJsonFormat.type = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
      ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates].map(r => Ok(write(r)))
    }

  def updateEgateBanksUpdates(): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            import spray.json._

            import EgateBanksJsonFormats.setEgateBanksUpdatesJsonFormat

            val setUpdate = text.parseJson.convertTo[SetEgateBanksUpdate]

            ctrl.egateBanksUpdatesActor.ask(setUpdate).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }
    }
  }

  def deleteEgateBanksUpdates(terminal: String, effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      ctrl.egateBanksUpdatesActor.ask(DeleteEgateBanksUpdates(Terminal(terminal), effectiveFrom)).map(_ => Accepted)
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
                case JsObject(bankObj) =>
                  bankObj.getOrElse("gates", throw new Exception(s"Expected gates field")) match {
                    case JsArray(bank) =>
                      EgateBank(bank.map {
                        case JsBoolean(gateIsOpen) => gateIsOpen
                      })
                    case unexpected => throw new Exception(s"Expected to find JsArray, but got ${unexpected.getClass}")
                  }
                case unexpected => throw new Exception(s"Expected to find JsObject, but got ${unexpected.getClass}")
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
            case (_, egateBanksUpdateJson) => egateBanksUpdateJson.convertTo[EgateBanksUpdate]
          }
          EgateBanksUpdates(egateBanksUpdates.toList)
      }
    }
  }

  implicit object portEgateBanksUpdatesJsonFormat extends RootJsonFormat[PortEgateBanksUpdates] {
    override def write(obj: PortEgateBanksUpdates): JsValue = JsArray(obj.updatesByTerminal.map {
      case (terminal, egateBanksUpdates) => JsObject(Map(
        "terminal" -> terminal.toString.toJson,
        "updates" -> egateBanksUpdates.toJson,
      ))
    }.toVector)

    override def read(json: JsValue): PortEgateBanksUpdates =
      throw new Exception("Deserialising PortEgateBanksUpdates is not implemented yet.")
  }

  implicit object terminalJsonFormat extends RootJsonFormat[Terminal] {
    override def write(obj: Terminal): JsValue = JsObject(Map(
      "$type" -> obj.getClass.toString.toJson
    ))

    override def read(json: JsValue): Terminal = json match {
      case JsObject(fields) => fields.get("$type") match {
        case Some(JsString(className)) => className.split('.').reverse.headOption match {
          case Some(terminalName) => Terminal(terminalName)
          case None => throw new Exception(s"Didn't find a valid terminal name in $className")
        }
        case _ => throw new Exception(s"Expected a JsString. None found")
      }
      case unexpected => throw new Exception(s"Expected a JsObject. Found ${unexpected.getClass}")
    }
  }

  implicit val setEgateBanksUpdatesJsonFormat: RootJsonFormat[SetEgateBanksUpdate] = jsonFormat(
    SetEgateBanksUpdate.apply,
    "terminal",
    "originalDate",
    "egateBanksUpdate"
  )

  implicit val portCodeJsonFormat: RootJsonFormat[PortCode] = jsonFormat(PortCode.apply, "iata")
}
