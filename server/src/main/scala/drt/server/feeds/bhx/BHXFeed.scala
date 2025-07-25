package drt.server.feeds.bhx

import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.xml.ScalaXmlSupport
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.unmarshalling.{FromResponseUnmarshaller, Unmarshal, Unmarshaller}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FeedArrival, FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try
import scala.xml.{Node, NodeSeq}

object BHXFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[FT](client: BHXClientLike, source: Source[FeedTick, FT])
               (implicit actorSystem: ActorSystem, materializer: Materializer): Source[ArrivalsFeedResponse, FT] = {
    var initialRequest = true
    source.mapAsync(1) { _ =>
      log.info(s"Requesting BHX Feed")
      if (initialRequest)
        client.initialFlights.map {
          case s: ArrivalsFeedSuccess =>
            initialRequest = false
            s
          case f: ArrivalsFeedFailure =>
            f
        }
      else
        client.updateFlights
    }
  }
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

final class SoapActionHeader(action: String) extends ModeledCustomHeader[SoapActionHeader] {
  override def renderInRequests = true

  override def renderInResponses = false

  override val companion: ModeledCustomHeaderCompanion[SoapActionHeader] = SoapActionHeader

  override def value: String = action
}

object SoapActionHeader extends ModeledCustomHeaderCompanion[SoapActionHeader] {
  override val name = "SOAPAction"

  override def parse(value: String): Try[SoapActionHeader] = Try(new SoapActionHeader(value))
}

trait BHXClientLike extends ScalaXmlSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val bhxLiveFeedUser: String
  val soapEndPoint: String

  def initialFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
    log.info(s"Making initial Live Feed Request")
    sendXMLRequest(fullRefreshXml(bhxLiveFeedUser))
  }

  def updateFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
    log.info(s"Making update Feed Request")
    sendXMLRequest(updateXml()(bhxLiveFeedUser))
  }

  def sendXMLRequest(postXml: String)(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
    implicit val xmlToResUM: Unmarshaller[NodeSeq, BHXFlightsResponse] = BHXFlight.unmarshaller
    implicit val resToBHXResUM: Unmarshaller[HttpResponse, BHXFlightsResponse] = BHXFlight.responseToAUnmarshaller

    val headers: List[HttpHeader] = List(
      SoapActionHeader("http://www.iata.org/IATA/2007/00/IRequestFlightService/RequestFlightData")
    )

    makeRequest(soapEndPoint, headers, postXml)
      .map(res => {
        log.info(s"Got a response from BHX ${res.status}")
        val bhxResponse = Unmarshal[HttpResponse](res).to[BHXFlightsResponse]

        bhxResponse.map {
          case s: BHXFlightsResponseSuccess =>
            ArrivalsFeedSuccess(s.flights.map(fs => BHXFlight.bhxFlightToArrival(fs)))

          case f: BHXFlightsResponseFailure =>
            ArrivalsFeedFailure(f.message)
        }
      })
      .flatMap(identity)
      .recoverWith {
        case f =>
          log.error(s"Failed to get BHX Live Feed", f)
          Future(ArrivalsFeedFailure(f.getMessage))
      }
  }

  def fullRefreshXml: String => String = postXMLTemplate(fullRefresh = "1")

  def updateXml(): String => String = postXMLTemplate(fullRefresh = "0")

  def postXMLTemplate(fullRefresh: String)(username: String): String = {
    val postXML =
      s"""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.iata.org/IATA/2007/00">
         |    <soapenv:Header/>
         |    <soapenv:Body>
         |        <ns:userID>$username</ns:userID>
         |        <ns:fullRefresh>$fullRefresh</ns:fullRefresh>
         |    </soapenv:Body>
         |</soapenv:Envelope>""".stripMargin
    postXML
  }

  def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)
                 (implicit system: ActorSystem): Future[HttpResponse]

}

case class BHXClient(bhxLiveFeedUser: String, soapEndPoint: String) extends BHXClientLike {
  def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)
                 (implicit system: ActorSystem): Future[HttpResponse] =
    Http().singleRequest(HttpRequest(HttpMethods.POST, endpoint, headers, HttpEntity(ContentTypes.`text/xml(UTF-8)`, postXML)))
}

trait NodeSeqUnmarshaller {
  implicit def responseToAUnmarshaller[A](implicit resp: FromResponseUnmarshaller[NodeSeq],
                                          toA: Unmarshaller[NodeSeq, A]): Unmarshaller[HttpResponse, A] = {
    resp.flatMap(toA).asScala
  }
}

sealed trait BHXFlightsResponse

case class BHXFlightsResponseSuccess(flights: List[BHXFlight]) extends BHXFlightsResponse

case class BHXFlightsResponseFailure(message: String) extends BHXFlightsResponse

object BHXFlight extends NodeSeqUnmarshaller {
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

  implicit val unmarshaller: Unmarshaller[NodeSeq, BHXFlightsResponse] = Unmarshaller.strict[NodeSeq, BHXFlightsResponse] { xml =>

    val flightNodeSeq = xml \ "Body" \ "IATA_AIDX_FlightLegRS" \ "FlightLeg"

    log.info(s"Got ${flightNodeSeq.length} flights in BHX XML")

    val flights = flightNodeSeq.map(n => {
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

    val warningNode = xml \ "Body" \ "IATA_AIDX_FlightLegRS" \ "Warnings" \ "Warning"

    val warnings = warningNode.map(w => {
      val typeCode = attributeFromNode(w, "Type").getOrElse("No error type code")
      s"Code: $typeCode  Message:${w.text}"
    })
    warnings.foreach(w => log.warn(s"BHX Live Feed warning: $w"))

    if (flights.isEmpty && warnings.nonEmpty)
      BHXFlightsResponseFailure(warnings.mkString(", "))
    else
      BHXFlightsResponseSuccess(flights)
  }

  def paxFromCabin(cabinPax: NodeSeq, seatingField: String): Option[Int] = cabinPax match {
    case cpn if cpn.length > 0 =>
      val seats: immutable.Seq[Option[Int]] = cpn.flatMap(p => {
        (p \ seatingField).map(seatingNode =>

          if (seatingNode.text.isEmpty)
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
    case t if t.nonEmpty => Option(t)
    case _ => None
  }

  def attributeFromNode(ot: Node, attributeName: String): Option[String] = ot.attribute(attributeName) match {
    case Some(node) => Some(node.text)
    case _ => None
  }

  def bhxFlightToArrival(f: BHXFlight): FeedArrival = {
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(f.airline + f.flightNumber)

    LiveArrival(
      operator = Option(f.airline),
      maxPax = f.seatCapacity,
      totalPax = f.paxCount,
      transPax = None,
      terminal = Terminal(s"T${f.aircraftTerminal}"),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = f.departureAirport,
      previousPort = None,
      scheduled = SDate(f.scheduledOnBlocks).millisSinceEpoch,
      estimated = maybeTimeStringToMaybeMillis(f.estimatedTouchDown),
      touchdown = maybeTimeStringToMaybeMillis(f.actualTouchDown),
      estimatedChox = None,
      actualChox = maybeTimeStringToMaybeMillis(f.actualOnBlocks),
      status = f.status,
      gate = f.passengerGate,
      stand = f.aircraftParkingPosition,
      runway = None,
      baggageReclaim = None,
    )
  }

  def maybeTimeStringToMaybeMillis(t: Option[String]): Option[MillisSinceEpoch] = t.flatMap(
    SDate.tryParseString(_).toOption.map(_.millisSinceEpoch)
  )
}
