package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import drt.server.feeds.lgw.GatwickAzureToken
import drt.shared.Arrival
import javax.xml.ws.BindingProvider
import org.slf4j.{Logger, LoggerFactory}
import uk.co.bhx.online.flightinformation.{FlightInformation, FlightInformationSoap}
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

    val connectionTimeout = 25000
    val receiveTimeout = 30000
    val pollFrequency = 4 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val endPointUrl = config.getString("feeds.birmingham.soap.endPointUrl")

    val serviceSoap: FlightInformationSoap =
      Try {
        val service: FlightInformation = new FlightInformation(this.getClass.getClassLoader.getResource("FlightInformation.wsdl"))
        log.debug(s"Initialising Birmingham Feed with ${service.getWSDLDocumentLocation.toString} [connectionTimeout: $connectionTimeout, receiveTimeout: $receiveTimeout]")
        service.getFlightInformationSoap match {
          case binder: BindingProvider =>
            binder.getRequestContext.put("javax.xml.ws.client.connectionTimeout", connectionTimeout.toString)
            binder.getRequestContext.put("javax.xml.ws.client.receiveTimeout", receiveTimeout.toString)
            if (!endPointUrl.isEmpty)
              binder.getRequestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endPointUrl)
            binder
          case flightInformationSoap => flightInformationSoap
        }
      }.recoverWith { case t => log.error(s"Failure starting Birmingham feed: ${t.getMessage}", t); null }.get

    val feed = BHXFeed(serviceSoap)

    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => {
        Try {
          log.info("About to get arrivals for Birmingham.")
          feed.getArrivals
        } match {
          case Success(arrivals) =>
            log.info(s"Got ${arrivals.size} Birmingham arrivals.")
            arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch BHX arrivals.", t)
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}