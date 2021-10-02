package drt.server.feeds.lcy

import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{FromResponseUnmarshaller, Unmarshaller}
import drt.server.feeds.Implicits._
import drt.server.feeds.common.FlightStatus
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable
import scala.xml.{Node, NodeSeq}

sealed trait LCYFlightsResponse

case class LCYFlightsResponseSuccess(flights: List[LCYFlight]) extends LCYFlightsResponse

case class LCYFlightsResponseFailure(message: String) extends LCYFlightsResponse

case class LCYFlight(airline: String,
                     flightNumber: String,
                     departureAirport: String,
                     arrivalAirport: String,
                     aircraftTerminal: String,
                     status: String,
                     scheduledOnBlocks: String,
                     arrival: Boolean,
                     international: Boolean,
                     estimatedOnBlocks: Option[String] = None,
                     actualOnBlocks: Option[String] = None,
                     estimatedTouchDown: Option[String] = None,
                     actualTouchDown: Option[String] = None,
                     aircraftParkingPosition: Option[String] = None,
                     passengerGate: Option[String] = None,
                     seatCapacity: Option[Int] = None,
                     paxCount: Option[Int] = None,
                     codeShares: List[String] = Nil
                    )

trait NodeSeqUnmarshaller {
  implicit def responseToAUnmarshaller[A](implicit resp: FromResponseUnmarshaller[NodeSeq],
                                          toA: Unmarshaller[NodeSeq, A]): Unmarshaller[HttpResponse, A] = {
    resp.flatMap(toA).asScala
  }
}


object LCYFlightTransform extends NodeSeqUnmarshaller {

  def operationTimeFromNodeSeq(timeType: String, qualifier: String)(nodeSeq: NodeSeq): Option[String] = {
    nodeSeq.find(p =>
      attributeFromNode(p, "OperationQualifier").contains(qualifier) &&
        attributeFromNode(p, "TimeType").contains(timeType)
    ).map(_.text)
  }

  def estTouchDown: NodeSeq => Option[String] = operationTimeFromNodeSeq("EST", "TDN")

  def actualTouchDown: NodeSeq => Option[String] = operationTimeFromNodeSeq("ACT", "TDN")

  def estChox: NodeSeq => Option[String] = operationTimeFromNodeSeq("EST", "ONB")

  def actualChox: NodeSeq => Option[String] = operationTimeFromNodeSeq("ACT", "ONB")

  val log: Logger = LoggerFactory.getLogger(getClass)

  def scheduledTime: NodeSeq => Option[String] = operationTimeFromNodeSeq("SCT", "ONB")

  def scheduledOFBTime: NodeSeq => Option[String] = operationTimeFromNodeSeq("SCT", "OFB")

  implicit val unmarshaller: Unmarshaller[NodeSeq, LCYFlightsResponse] = Unmarshaller.strict[NodeSeq, LCYFlightsResponse] { xml =>

    val flightNodeSeq = xml \ "Body" \ "IATA_AIDX_FlightLegRS" \ "FlightLeg"

    log.info(s"Got ${flightNodeSeq.length} flights in LCY XML")

    val flights = flightNodeSeq.map(n => {
      val airline = (n \ "LegIdentifier" \ "Airline").text
      val flightNumber = (n \ "LegIdentifier" \ "FlightNumber").text
      val arrivalAirport = (n \ "LegIdentifier" \ "ArrivalAirport").text
      val departureAirport = (n \ "LegIdentifier" \ "DepartureAirport").text
      val aircraftTerminal = (n \ "LegData" \ "AirportResources" \ "Resource" \ "AircraftTerminal").text
      val status = (n \ "LegData" \ "RemarkFreeText").text
      val airportParkingLocation = maybeNodeText(n \ "LegData" \ "AirportResources" \ "Resource" \ "AircraftParkingPosition")
      val passengerGate = maybeNodeText(n \ "LegData" \ "AirportResources" \ "Resource" \ "PassengerGate")

      val cabins = n \ "LegData" \ "CabinClass"
      val maxPax = paxFromCabin(cabins, "SeatCapacity")
      val totalPax = paxFromCabin(cabins, "PaxCount")

      val operationTimes = n \ "LegData" \ "OperationTime"

      val scheduledOnBlocks = scheduledTime(operationTimes).getOrElse(scheduledOFBTime(operationTimes).get)
      val maybeActualTouchDown = actualTouchDown(operationTimes)
      val maybeEstTouchDown = estTouchDown(operationTimes)
      val maybeEstChox = estChox(operationTimes)
      val maybeActualChox = actualChox(operationTimes)


      LCYFlight(
        airline,
        flightNumber,
        departureAirport,
        arrivalAirport,
        aircraftTerminal,
        status,
        scheduledOnBlocks,
        arrival = true,
        international = true,
        maybeEstChox,
        maybeActualChox,
        maybeEstTouchDown,
        maybeActualTouchDown,
        airportParkingLocation,
        passengerGate,
        maxPax,
        totalPax
      )
    }).toList

    val warningNode = xml \ "Body" \ "IATA_AIDX_FlightLegRS" \ "Warnings" \ "Warning"

    val warnings = warningNode.map(w => {
      val typeCode = attributeFromNode(w, "Type").getOrElse("No error type code")
      s"Code: $typeCode  Message:${w.text}"
    })
    warnings.foreach(w => log.warn(s"LCY Live Feed warning: $w"))

    if (flights.isEmpty && warnings.nonEmpty)
      LCYFlightsResponseFailure(warnings.mkString(", "))
    else
      LCYFlightsResponseSuccess(flights)
  }

  def paxFromCabin(cabinPax: NodeSeq, seatingField: String): Option[Int] = cabinPax match {
    case cpn if cpn.length > 0 =>
      val seats: immutable.Seq[Option[Int]] = cpn.flatMap(p => {
        (p \ seatingField).map(seatingNode =>

          if (seatingNode.text.length == 0)
            None
          else
            maybeNodeText(seatingNode).map(_.toInt)
        )
      })
      if (seats.count(_.isDefined) > 0)
        Option(seats.flatten.sum)
      else
        None

    case _ => None
  }

  def maybeNodeText(n: NodeSeq): Option[String] = n.text match {
    case t if t.length > 0 => Option(t)
    case _ => None
  }

  def attributeFromNode(ot: Node, attributeName: String): Option[String] = ot.attribute(attributeName) match {
    case Some(node) => Some(node.text)
    case _ => None
  }

  def lcyFlightToArrival(f: LCYFlight) = Arrival(
      f.airline,
      FlightStatus(f.status),
      maybeTimeStringToMaybeMillis(f.estimatedTouchDown),
      maybeTimeStringToMaybeMillis(f.actualTouchDown),
      None,
      maybeTimeStringToMaybeMillis(f.actualOnBlocks),
      f.passengerGate,
      f.aircraftParkingPosition,
      f.seatCapacity,
      f.paxCount,
      None,
      None,
      None,
      f.arrivalAirport,
      Terminal(f.aircraftTerminal),
      f.airline + f.flightNumber,
      f.airline + f.flightNumber,
      f.departureAirport,
      SDate(f.scheduledOnBlocks).millisSinceEpoch,
      None,
      Set(LiveFeedSource)
    )


  def maybeTimeStringToMaybeMillis(t: Option[String]): Option[MillisSinceEpoch] = t.flatMap(
    SDate.tryParseString(_).toOption.map(_.millisSinceEpoch)
  )
}
