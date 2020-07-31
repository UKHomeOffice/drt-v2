package services.arrivals

import java.net.URL

import drt.shared.Terminals.Terminal
import drt.shared.{ArrivalsDiff, PortCode, UniqueArrival}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

trait ArrivalsAdjustmentsLike {

  def apply(arrivalsDiff: ArrivalsDiff, arrivalsKeys: Iterable[UniqueArrival]): ArrivalsDiff

}

object ArrivalsAdjustments {
  val log = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode, maybeCsvUrl: Option[String]): ArrivalsAdjustmentsLike =
    if (portCode == PortCode("EDI")) {
      val historicTerminalMap: Map[String, Map[String, Terminal]] = maybeCsvUrl
        .map(url =>
          EdiArrivalTerminalCsvMapper(new URL(url)) match {
            case Success(arrivalsMap) =>
              log.info(s"Using EdiArrivalsTerminalAdjustments with Historic CSV")
              arrivalsMap
            case Failure(exception) =>
              log.error(
                "Failed to load EDI terminal Map CSV - using EdiArrivalsTerminalAdjustments with no historic csv",
                exception
              )
              Map[String, Map[String, Terminal]]()
          }
        ).getOrElse(Map[String, Map[String, Terminal]]())
      EdiArrivalsTerminalAdjustments(historicTerminalMap)
    }
    else {
      log.info(s"Using  ArrivalsAdjustmentsNoop")
      ArrivalsAdjustmentsNoop
    }
}
