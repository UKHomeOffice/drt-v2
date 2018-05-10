package drt.server.feeds.lgw

import java.io.ByteArrayInputStream

import drt.shared.Arrival
import org.apache.commons.io.IOUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node}

case class ResponseToArrivals(data: Array[Byte], locationOption: Option[String] ) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def getArrivals: List[(Arrival, Option[String])] = {
    val is = new ByteArrayInputStream(data)
    val xmlTry = Try(scala.xml.XML.load(is)).recoverWith {
      case e: Throwable =>
        log.error(s"Cannot load Gatwick XML from the response: ${new String(data)}.", e)
        throw e
    }
    IOUtils.closeQuietly(is)
    Try {
      for {
        xml <- xmlTry.toOption.toSeq
        node <- scala.xml.Utility.trimProper(xml)
      } yield nodeToArrival(node)
    } match {
      case Success(arrivalsAndLocation) => arrivalsAndLocation.toList
      case Failure(t) =>
        log.error(s"Failed to get an Arrival from the Gatwick XML. ${t.getMessage}. ${xmlTry.getOrElse("")}.", t)
        List.empty[(Arrival, Option[String])]
    }
  }

  def nodeToArrival: Node => (Arrival, Option[String]) = (n: Node) => {

    val arrival = new Arrival(
      Operator = (n \ "AirlineIATA") text,
      Status = parseStatus(n),
      EstDT = parseDateTime(n, "TDN", "EST").getOrElse(""),
      ActDT = parseDateTime(n, "TDN", "ACT").getOrElse(""),
      EstChoxDT = parseDateTime(n, "ONB", "EST").getOrElse(""),
      ActChoxDT = parseDateTime(n, "ONB", "ACT").getOrElse(""),
      Gate = (n \\ "PassengerGate").headOption.map(n => n text).getOrElse(""),
      Stand = (n \\ "ArrivalStand").headOption.map(n => n text).getOrElse(""),
      MaxPax = (n \\ "SeatCapacity").headOption.map(n => (n text).toInt).getOrElse(0),
      ActPax = parsePaxCount(n, "70A").getOrElse(0),
      TranPax = parsePaxCount(n, "TIP").getOrElse(0),
      RunwayID = parseRunwayId(n).getOrElse(""),
      BaggageReclaimId = Try(n \\ "FIDSBagggeHallActive" text).getOrElse(""),
      FlightID = 0,
      AirportID = "LGW",
      Terminal = parseTerminal(n),
      rawICAO = (n \\ "AirlineICAO" text) + parseFlightNumber(n),
      rawIATA = (n \\ "AirlineIATA" text) + parseFlightNumber(n),
      Origin = parseOrigin(n),
      SchDT = (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text).equals("SCT")).map(n => n text).getOrElse(""),
      Scheduled = (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text).equals("SCT")).map(n => services.SDate.parseString(n text).millisSinceEpoch).getOrElse(0),
      PcpTime = 0,
      LastKnownPax = None)
    log.info(s"parsed arrival: $arrival")
    (arrival, locationOption)
  }

  private def parseTerminal(n: Node): String = {
    val terminal = (n \\ "AirportResources" \ "Resource").find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "AircraftTerminal" text).getOrElse("")
    val mappedTerminal = terminal match {
      case "1" => "S"
      case "2" => "N"
      case _ => ""
    }
    mappedTerminal
  }

  private def parseFlightNumber(n: Node) = {
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "FlightNumber" text
  }

  def parseStatus(n: Node): String = {
    ((n \ "FlightLeg").head \ "LegData").head \ "OperationalStatus" text
  }

  def parseOrigin(n: Node): String = {
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "DepartureAirport" text
  }

  def parseRunwayId(n: Node): Option[String] = {
    (n \\ "AirportResources" \ "Resource").find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "Runway" text)
  }

  def parsePaxCount(n: Node, qualifier: String): Option[Int] = {
    (n \\ "PaxCount").find(n => (n \ "@Qualifier" text).equals(qualifier) && (n \ "@Class").isEmpty).map(n => (n text).toInt)
  }

  def parseDateTime(n: Node, operationQualifier: String, timeType: String): Option[String] = {
    (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text).equals("SCT")).map(n => n text)
    (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals(operationQualifier) && (n \ "@TimeType" text).equals(timeType)).map(n => n text)
  }

}
