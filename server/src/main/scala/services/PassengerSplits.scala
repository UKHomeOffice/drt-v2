package services

import akka.pattern.AskableActorRef
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import controllers.AirportConfProvider
import drt.shared.PassengerSplits.{FlightNotFound, VoyagePaxSplits}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{AirportConfig, ApiFlight, FlightParsing, PaxTypeAndQueue}
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import services.SDate.implicits._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

object AdvPaxSplitsProvider {
  val log = LoggerFactory.getLogger(getClass)

  def splitRatioProvider (destinationPort: String)
                        (passengerInfoRouterActor: AskableActorRef)
                        (flight: ApiFlight)
                        (implicit timeOut: Timeout, ec: ExecutionContext): Option[SplitRatios] = {
    log.debug(s"splitRatioProvider for ${flight}")
    log.info(s"splitRatioProvider for ${flight.IATA} ${flight}")
    FlightParsing.parseIataToCarrierCodeVoyageNumber(flight.IATA) match {
      case Some((cc, number)) =>
        val split = ReportVoyagePaxSplit(flight.AirportID, flight.Operator, number, SDate(flight.SchDT))
        log.info(s"splitRatioProvider asks ${split}")
        val futResp = passengerInfoRouterActor ? split
        val splitsFut = futResp.map {
          case voyagePaxSplits: VoyagePaxSplits =>
            log.info(s"splitRatioProvider didgotsplitcrunch for ${flight} ${voyagePaxSplits}")
            Some(convertVoyagePaxSplitPeopleCountsToSplitRatios(voyagePaxSplits))
          case fnf: FlightNotFound =>
            log.info(s"splitRatioProvider notgotsplitcrunch for $cc/${number} ${flight}")
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

