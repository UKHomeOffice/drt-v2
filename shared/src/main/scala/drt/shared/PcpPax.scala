package drt.shared

import drt.shared.api.Arrival

object PcpPax {
  val defaultPax = 0

  def bestPax(flight: Arrival): Int = {
    (flight.ApiPax, flight.ActPax, flight.TranPax, flight.MaxPax) match {
      case (Some(apiPax), _, _, _) => apiPax
      case (_, Some(actPax), Some(tranPax), _) if (actPax - tranPax) >= 0 => actPax - tranPax
      case (_, Some(actPax), None, _) => actPax
      case (_, _, _, Some(maxPax)) if maxPax > 0 => maxPax
      case _ => defaultPax
    }
  }

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
  }
}
