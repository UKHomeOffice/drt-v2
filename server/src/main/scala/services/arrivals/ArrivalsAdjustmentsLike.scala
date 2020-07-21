package services.arrivals

import java.net.URL

import drt.shared.Terminals.Terminal
import drt.shared.{ArrivalsDiff, PortCode}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

trait ArrivalsAdjustmentsLike {

  def apply(arrivalsDiff: ArrivalsDiff): ArrivalsDiff

}

object ArrivalsAdjustments {
  val log = LoggerFactory.getLogger(getClass)

  def adjustmentsForPort(portCode: PortCode, maybeCsvUrl: Option[String]): ArrivalsAdjustmentsLike =
    if (portCode == PortCode("EDI")) {
      val historicTerminalMap: Map[String, Map[String, Terminal]] = maybeCsvUrl
        .map(url =>
          EdiArrivalTerminalCsvMapper(new URL(url)) match {
            case Success(arrivalsMap) =>
              arrivalsMap
            case Failure(exception) =>
              log.error("Failed to load EDI terminal Map CSV - only live terminal mapping available", exception)
              Map[String, Map[String, Terminal]]()
          }
        ).getOrElse(Map[String, Map[String, Terminal]]())
      EdiArrivalsTerminalAdjustments(historicTerminalMap)
    }
    else
      ArrivalsAdjustmentsNoop
}
