package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.{FromResponseUnmarshaller, Unmarshal, Unmarshaller}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, LiveFeedSource}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import scala.xml.{Node, NodeSeq}

object BHXFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(bhxLiveFeedUser: String, bhxSoapEndpoint: String)(implicit actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {

    val client = BHXClient(bhxLiveFeedUser, bhxSoapEndpoint)
    val pollFrequency = 5 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .mapAsync(1)(_ => {
        log.info(s"Requesting BHX Feed")
        client.flights
          .map(bHXFlights => bHXFlights.map(f => {
            bhxFlightToArrival(f)
          })).map(a => {
          log.info(s"Received ${a.length} flights from BHX live feed")
          log.info(s"Sending These 10 ${a.take(10)}")
          ArrivalsFeedSuccess(Flights(a))
        })
      })

    tickingSource
  }

  def bhxFlightToArrival(f: BHXFlight) = {
    Arrival(
      Option(f.airline),
      BHXFlightStatus(f.status),
      maybeTimeStringToMaybeMillis(f.estimatedTouchDown),
      maybeTimeStringToMaybeMillis(f.actualTouchDown),
      maybeTimeStringToMaybeMillis(f.estimatedOnBlocks),
      maybeTimeStringToMaybeMillis(f.actualOnBlocks),
      f.passengerGate,
      f.aircraftParkingPosition,
      f.seatCapacity,
      f.paxCount,
      None,
      None,
      None,
      None,
      f.arrivalAirport,
      s"T${f.aircraftTerminal}",
      f.airline + f.flightNumber,
      f.airline + f.flightNumber,
      f.departureAirport,
      SDate(f.scheduledOnBlocks).millisSinceEpoch,
      None,
      Set(LiveFeedSource),
      None
    )
  }

  def maybeTimeStringToMaybeMillis(t: Option[String]): Option[MillisSinceEpoch] = t.flatMap(
    SDate.tryParseString(_).toOption.map(_.millisSinceEpoch)
  )

}

case class BHXFlight(
                      airline: String,
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

object BHXFlightStatus {
  val statusCodes = Map(
    "AFC" -> "AF CHECK-IN OPEN",
    "AIR" -> "AIRBORNE",
    "ARR" -> "ARRIVED ON STAND",
    "BBR" -> "BOARDING BY ROW",
    "BBZ" -> "BOARDING BY ZONE",
    "BDG" -> "BOARDING",
    "BOB" -> "BAGGAGE IN HALL",
    "CAN" -> "CANCELLED",
    "CIC" -> "CHECK-IN CLOSED",
    "CIO" -> "CHECK-IN OPEN",
    "DEP" -> "DEPARTED (PUSH BACK)",
    "DIV" -> "DIVERTED",
    "DLO" -> "DELAYED OPERATIONAL",
    "EGC" -> "GATE CLOSED",
    "ETD" -> "ESTIMATED TIME DEPARTURE",
    "EXP" -> "EXPECTED",
    "EZS" -> "EZS CHECK-IN OPEN",
    "EZY" -> "EZY CHECK-IN OPEN",
    "FCL" -> "FINAL CALL",
    "FTJ" -> "FAILED TO JOIN",
    "GTO" -> "GATE OPEN",
    "IND" -> "INDEFINTE DELAY",
    "LBB" -> "LAST BAG DELIVERED",
    "LND" -> "LANDED",
    "MDB" -> "THOMAS COOK DAY BEFORE CHECK IN",
    "NXT" -> "NEXT INFORMATION AT",
    "ONA" -> "ON APPROACH",
    "RSH" -> "RESCHEDULED",
    "STA" -> "SCHEDULED TIME OF ARRIVAL",
    "TDB" -> "THOMSON DAY BEFORE CHECK IN",
    "TOM" -> "TOM CHECK-IN OPEN",
    "WAI" -> "WAIT IN LOUNGE"
  )

  def apply(code: String) = statusCodes.getOrElse(code, code)
}


final class SoapActionHeader(action: String) extends ModeledCustomHeader[SoapActionHeader] {
  override def renderInRequests = true

  override def renderInResponses = false

  override val companion = SoapActionHeader

  override def value: String = action
}

object SoapActionHeader extends ModeledCustomHeaderCompanion[SoapActionHeader] {
  override val name = "SOAPAction"

  override def parse(value: String) = Try(new SoapActionHeader(value))
}

case class BHXClient(bhxLiveFeedUser: String, soapEndPoint: String) extends ScalaXmlSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def flights(implicit actorSystem: ActorSystem): Future[List[BHXFlight]] = {

    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val endpoint = soapEndPoint

    val headers: List[HttpHeader] = List(
      SoapActionHeader("http://www.iata.org/IATA/2007/00/IRequestFlightService/RequestFlightData")
    )

    val username = bhxLiveFeedUser
    val postXML =
      s"""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.iata.org/IATA/2007/00">
         |    <soapenv:Header/>
         |    <soapenv:Body>
         |        <ns:userID>$username</ns:userID>
         |        <ns:fullRefresh>1</ns:fullRefresh>
         |    </soapenv:Body>
         |</soapenv:Envelope>""".stripMargin

    Http().singleRequest(HttpRequest(HttpMethods.POST, endpoint, headers, HttpEntity(ContentTypes.`text/xml(UTF-8)`, postXML)))
      .map(res => {
        log.info(s"Got a response from BHX ${res.status}")
        Unmarshal[HttpResponse](res).to[List[BHXFlight]]
      })
      .flatMap(identity)
      .recoverWith {
        case f =>
          log.error(s"Failed to get BHX Live Feed", f)
          Future(List())
      }
  }
}

trait NodeSeqUnmarshaller {
  implicit def responseToAUnmarshaller[A](implicit resp: FromResponseUnmarshaller[NodeSeq],
                                          toA: Unmarshaller[NodeSeq, A]): Unmarshaller[HttpResponse, A] = {
    resp.flatMap(toA).asScala
  }
}

object BHXFlight extends NodeSeqUnmarshaller {
  def operationTimeFromNodeSeq(timeType: String, qualifier: String)(nodeSeq: NodeSeq) = {
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

  implicit val unmarshaller: Unmarshaller[NodeSeq, List[BHXFlight]] = Unmarshaller.strict[NodeSeq, List[BHXFlight]] { xml =>


    val flightNodeSeq = xml \ "Body" \ "IATA_AIDX_FlightLegRS" \ "FlightLeg"

    log.info(s"Got ${flightNodeSeq.length} flights in BHX XML")
    flightNodeSeq.map(n => {
      val airline = (n \ "LegIdentifier" \ "Airline").text
      val flightNumber = (n \ "LegIdentifier" \ "FlightNumber").text
      val departureAirport = (n \ "LegIdentifier" \ "DepartureAirport").text
      val aircraftTerminal = (n \ "LegData" \ "AirportResources" \ "Resource" \ "AircraftTerminal").text
      val status = (n \ "LegData" \ "RemarkFreeText").text
      val airportParkingLocation = maybeNodeText(n \ "LegData" \ "AirportResources" \ "Resource" \ "AircraftParkingPosition")
      val passengerGate = maybeNodeText(n \ "LegData" \ "AirportResources" \ "Resource" \ "PassengerGate")

      val cabins = n \ "LegData" \ "CabinClass"

      val maxPax = paxFromCabin(cabins, "SeatCapacity")
      val totalPax = paxFromCabin(cabins, "PaxCount")

      val operationTimes = n \ "LegData" \ "OperationTime"

      val scheduledOnBlocks = scheduledTime(operationTimes).get
      val maybeActualTouchDown = actualTouchDown(operationTimes)
      val maybeEstTouchDown = estTouchDown(operationTimes)
      val maybeEstChox = estChox(operationTimes)
      val maybeActualChox = actualChox(operationTimes)

      BHXFlight(
        airline,
        flightNumber,
        departureAirport,
        "BHX",
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
  }

  def paxFromCabin(cabinPax: NodeSeq, seatingField: String): Option[Int] = cabinPax match {
    case cpn if cpn.length > 0 =>
      val seats: immutable.Seq[Option[Int]] = cpn.map(p => {
        val seatingNode = p \ seatingField
        if (seatingNode.text.length == 0)
          None
        else
          maybeNodeText(seatingNode).map(_.toInt)
      }
      )
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
}
