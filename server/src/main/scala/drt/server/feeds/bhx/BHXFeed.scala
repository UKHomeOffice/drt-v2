package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import drt.server.feeds.lgw.GatwickAzureToken
import drt.shared.Arrival
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.ws.BindingProvider
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import uk.co.bhx.online.flightinformation.{FlightInformation, FlightInformationSoap, FlightRecord, ScheduledFlightRecord}

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class BHXFeed(serviceSoap: FlightInformationSoap) extends BHXLiveArrivals with BHXForecastArrivals {

  def getArrivals: List[Arrival] = {
    val flightRecords = serviceSoap.bfGetFlights.getFlightRecord.toList
    flightRecords.map(toLiveArrival)
  }



  def getForecastArrivals: List[Arrival] = {
    val flights = serviceSoap.bfGetScheduledFlights().getScheduledFlightRecord.toList
    flights.map(toForecastArrival)
  }

}

object BHXFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)
  var tokenFuture: Future[GatwickAzureToken] = _

  def apply()(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {
    val config = actorSystem.settings.config

    val connectionTimeout = config.getInt("feeds.birmingham.soap.connection_timeout")
    val receiveTimeout = config.getInt("feeds.birmingham.soap.receive_timeout")
    val pollFrequency = config.getInt("feeds.birmingham.soap.poll_frequency_in_minutes") minutes
    val initialDelayImmediately: FiniteDuration = config.getInt("feeds.birmingham.soap.initial_delay_in_milliseconds") milliseconds

    val serviceSoap: FlightInformationSoap =
      Try {
        val service: FlightInformation = new FlightInformation(this.getClass.getClassLoader.getResource("FlightInformation.wsdl"))
        log.debug(s"Initialising Birmingham Feed with ${service.getWSDLDocumentLocation.toString} [connectionTimeout: $connectionTimeout, receiveTimeout: $receiveTimeout]")
        service.getFlightInformationSoap match {
          case binder: BindingProvider =>
            binder.getRequestContext.put("javax.xml.ws.client.connectionTimeout", connectionTimeout.toString)
            binder.getRequestContext.put("javax.xml.ws.client.receiveTimeout", receiveTimeout.toString)
            binder
          case flightInformationSoap => flightInformationSoap
        }
      }.recoverWith { case t => log.error(s"Failure starting Birmingham feed: ${t.getMessage}", t); null }.get

    val feed = BHXFeed(serviceSoap)


    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => {
        Try {
          feed.getArrivals
        } match {
          case Success(arrivals) => arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch BHX arrivals.", t)
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}