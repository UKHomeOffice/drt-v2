package drt.server.feeds.bhx

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
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

  def apply(forecast: Boolean = false)(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {
    val config = actorSystem.settings.config

    val forecastOrLive = if (forecast) "forecast" else "live"

    val connectionTimeout = 25000
    val receiveTimeout = 30000
    val pollFrequency = 4 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val endPointUrl = config.getString("feeds.bhx.soap.endPointUrl")

    val serviceSoap: FlightInformationSoap =
      Try {
        val service: FlightInformation = new FlightInformation(this.getClass.getClassLoader.getResource("FlightInformation.wsdl"))
        log.debug(s"Initialising BHX $forecastOrLive Feed with ${service.getWSDLDocumentLocation.toString} [connectionTimeout: $connectionTimeout, receiveTimeout: $receiveTimeout]")
        service.getFlightInformationSoap match {
          case binder: BindingProvider =>
            binder.getRequestContext.put("javax.xml.ws.client.connectionTimeout", connectionTimeout.toString)
            binder.getRequestContext.put("javax.xml.ws.client.receiveTimeout", receiveTimeout.toString)
            if (!endPointUrl.isEmpty)
              binder.getRequestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endPointUrl)
            binder
          case flightInformationSoap => flightInformationSoap
        }
      }.recoverWith { case t => log.error(s"Failure starting BHX $forecastOrLive feed: ${t.getMessage}", t); null }.get

    val feed = BHXFeed(serviceSoap)

    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => {
        Try {
          log.info(s"About to get arrivals for BHX $forecastOrLive.")
          if (forecast) feed.getForecastArrivals else  feed.getArrivals
        } match {
          case Success(arrivals) =>
            log.info(s"Got ${arrivals.size} BHX arrivals $forecastOrLive.")
            arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch BHX arrivals $forecastOrLive.", t)
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}