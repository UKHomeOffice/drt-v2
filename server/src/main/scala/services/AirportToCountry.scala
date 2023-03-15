
package services

import drt.shared.CrunchApi._
import drt.shared._
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Codec
import scala.language.postfixOps
import scala.util.Try

trait AirportToCountryLike {
  lazy val airportInfoByIataPortCode: Map[String, AirportInfo] = {
    val bufferedSource = scala.io.Source.fromURL(getClass.getResource("/airports.dat"))(Codec.UTF8)
    bufferedSource.getLines().map { l =>
      val t = Try {
        val splitRow: Array[String] = l.split(",")
        val sq: String => String = stripQuotes
        AirportInfo(sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)), sq(splitRow(4)))
      }
      t.getOrElse({
        AirportInfo("failed on", l, "boo", "ya")
      })
    }.map(ai => (ai.code, ai)).toMap
  }

  def stripQuotes(row1: String): String = {
    row1.substring(1, row1.length - 1)
  }

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]] = Future {
    val res = codes.map(code => (code, airportInfoByIataPortCode.get(code)))

    val successes: Set[(String, AirportInfo)] = res collect {
      case (code, Some(ai)) =>
        (code, ai)
    }

    successes.toMap
  }
}

object AirportToCountry extends AirportToCountryLike {
  def isRedListed(portToCheck: PortCode, forDate: MillisSinceEpoch, redListUpdates: RedListUpdates): Boolean = airportInfoByIataPortCode
    .get(portToCheck.iata)
    .exists(ai => redListUpdates.countryCodesByName(forDate).contains(ai.country))
}



