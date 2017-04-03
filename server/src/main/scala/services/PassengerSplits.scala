package services

import akka.pattern.AskableActorRef
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{AirportConfig, ApiFlight, FlightParsing, PaxTypeAndQueue}
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object AdvPaxSplitsProvider {
  val log = LoggerFactory.getLogger(getClass)

  def splitRatioProvider (destinationPort: String)
                        (passengerInfoRouterActor: AskableActorRef)
                        (flight: ApiFlight)
                        (implicit timeOut: Timeout, ec: ExecutionContext): Option[SplitRatios] = {
    log.debug(s"${flight.IATA} splitRatioProvider")
    FlightParsing.parseIataToCarrierCodeVoyageNumber(flight.IATA) match {
      case Some((cc, number)) =>
        val splitRequest = ReportVoyagePaxSplit(flight.AirportID, flight.Operator, number, SDate(flight.SchDT))
        def logSr(mm: => String) = log.info(s"$splitRequest $mm")
        logSr("sending request")
        val futResp = passengerInfoRouterActor ? splitRequest
        val splitsFut = futResp.map {
          case voyagePaxSplits: VoyagePaxSplits =>
            log.info(s"${flight.IATA} didgotsplitcrunch")
            Some(convertVoyagePaxSplitPeopleCountsToSplitRatios(voyagePaxSplits))
          case _: FlightNotFound =>
            log.debug(s"${flight.IATA} notgotsplitcrunch")
            None
        }
        splitsFut.onFailure {
          case t: Throwable =>
            log.error(s"Failure to get splits in crunch for $flight ", t)
        }
        Await.result(splitsFut, 10 second)
    }
  }

  def convertVoyagePaxSplitPeopleCountsToSplitRatios(splits: VoyagePaxSplits) = {
    SplitRatios(splits.paxSplits
      .map(split => SplitRatio(
        PaxTypeAndQueue(split), split.paxCount.toDouble / splits.totalPaxCount)))
  }

}

object SplitsProvider {
  type SplitProvider = (ApiFlight) => Option[SplitRatios]

  def splitsForFlight(providers: List[SplitProvider])(apiFlight: ApiFlight): Option[SplitRatios] = {
    providers.foldLeft(None: Option[SplitRatios])((prev, provider) => {
      prev match {
        case Some(split) => prev
        case None => provider(apiFlight)
      }
    })
  }

  def shouldUseCsvSplitsProvider: Boolean = {
    val config: Config = ConfigFactory.load

    config.hasPath("passenger_splits_csv_url") && config.getString("passenger_splits_csv_url") != ""
  }

  def emptyProvider: SplitProvider = _ => Option.empty[SplitRatios]

  def csvProvider: (ApiFlight) => Option[SplitRatios] = {
    if (shouldUseCsvSplitsProvider)
      CSVPassengerSplitsProvider(CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig).splitRatioProvider
    else
      emptyProvider
  }

  def defaultProvider(airportConf: AirportConfig): (ApiFlight) => Some[SplitRatios] = {
    _ => Some(airportConf.defaultPaxSplits)
  }

}

