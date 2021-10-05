package drt.shared

import drt.shared.api.FlightCodeSuffix
import scala.util.matching.Regex

case class FlightCode(
                       carrierCode: CarrierCode,
                       voyageNumberLike: VoyageNumberLike,
                       maybeFlightCodeSuffix: Option[FlightCodeSuffix]) {

  val suffixString: String = maybeFlightCodeSuffix.map(_.suffix).getOrElse("")

  override def toString = s"${carrierCode}${voyageNumberLike.toPaddedString}$suffixString"

}

object FlightCode {
  val iataRe: Regex = "^([A-Z0-9]{2}?)([0-9]{1,4})([A-Z]*)$".r
  val icaoRe: Regex = "^([A-Z]{2,3}?)([0-9]{1,4})([A-Z]*)$".r

  def flightCodeToParts(code: String): (CarrierCode, VoyageNumberLike, Option[FlightCodeSuffix]) = code match {
    case iataRe(cc, vn, suffix) => stringsToComponents(cc, vn, suffix)
    case icaoRe(cc, vn, suffix) => stringsToComponents(cc, vn, suffix)
    case _ => (CarrierCode(""), VoyageNumber(0), None)
  }

  def apply(rawIATA: String, rawICAO: String): FlightCode = {
    FlightCode(bestCode(rawIATA, rawICAO))
  }

  def apply(code: String): FlightCode = {
    val (carrierCode: CarrierCode, voyageNumber: VoyageNumber, maybeSuffix: Option[FlightCodeSuffix]) = {

      FlightCode.flightCodeToParts(code)
    }
    FlightCode(carrierCode, voyageNumber, maybeSuffix)
  }

  def bestCode(rawIATA: String, rawICAO: String): String = (rawIATA, rawICAO) match {
    case (iata, _) if iata != "" => iata
    case (_, icao) if icao != "" => icao
    case _ => ""
  }

  private def stringsToComponents(cc: String,
                                  vn: String,
                                  suffix: String): (CarrierCode, VoyageNumberLike, Option[FlightCodeSuffix]) = {
    val carrierCode = CarrierCode(cc)
    val voyageNumber = VoyageNumber(vn)
    val arrivalSuffix = if (suffix.nonEmpty) Option(FlightCodeSuffix(suffix)) else None
    (carrierCode, voyageNumber, arrivalSuffix)
  }
}