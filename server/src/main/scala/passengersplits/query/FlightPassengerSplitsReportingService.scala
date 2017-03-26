package passengersplits.query

import akka.pattern.AskableActorRef
import akka.util.Timeout
import org.joda.time.DateTime
import passengersplits.core
import passengersplits.core.PassengerInfoRouterActor._
import services.SDate.implicits._
import drt.shared.SDateLike

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex.Match


object FlightPassengerSplitsReportingService {
  def parseUrlDateTime(notQuiteIsoDatetime: String) = {
    val dateTimeRe = """(\d\d\d\d)(\d\d)(\d\d)T(\d\d)(\d\d)""".r
    val matches: Option[Match] = dateTimeRe.findFirstMatchIn(notQuiteIsoDatetime)
    matches match {
      case Some(reMatch) =>
        val isoDt = s"${reMatch.group(1)}-${reMatch.group(2)}-${reMatch.group(3)}T${reMatch.group(4)}:${reMatch.group(5)}:00"
        DateTime.parse(isoDt)
      case None => None
    }
  }

  def calculateSplits(aggregator: AskableActorRef)
                     (destPort: String, terminalName: String, flightCode: String, arrivalTime: SDateLike)(implicit timeout: Timeout, ec: ExecutionContext) = {
    getCarrierCodeAndFlightNumber(flightCode) match {
      case Some((cc, fn)) => aggregator ? ReportVoyagePaxSplit(destPort, cc, fn, arrivalTime)
      case None => Future.failed(new Exception(s"couldn't get carrier and voyage number from $flightCode"))
    }
  }

  def calculateSplitsFromTimeRange(aggregator: AskableActorRef)
                                  (destPort: String, arrivalTimeFrom: DateTime, arrivalTimeTo: DateTime)
                                  (implicit timeout: Timeout, ec: ExecutionContext) = {
    aggregator ? ReportVoyagePaxSplitBetween(destPort, arrivalTimeFrom, arrivalTimeTo)
  }

  val flightCodeRe = """(\w{2})(\d{1,5})""".r("carrierCode", "voyageNumber")

  def getCarrierCodeAndFlightNumber(flightCode: String) = {
    flightCodeRe.findFirstMatchIn(flightCode) match {
      case Some(matches) => Some((matches.group("carrierCode"), matches.group("voyageNumber")))
      case None => None
    }
  }
}
