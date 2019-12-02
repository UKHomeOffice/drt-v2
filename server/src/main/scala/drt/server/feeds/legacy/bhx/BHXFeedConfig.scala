package drt.server.feeds.legacy.bhx

import javax.xml.ws.BindingProvider
import org.slf4j.Logger
import uk.co.bhx.online.flightinformation.{FlightInformation, FlightInformationSoap}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait BHXFeedConfig {
  val log: Logger
  val connectionTimeout = 25000
  val receiveTimeout = 30000
  val pollFrequency: FiniteDuration = 30 seconds
  val initialDelayImmediately: FiniteDuration = 1 milliseconds

  def serviceSoap(endPointUrl: String): FlightInformationSoap = {
    Try {
      val service: FlightInformation = new FlightInformation(this.getClass.getClassLoader.getResource("FlightInformation.wsdl"))
      log.debug(s"Initialising BHX Feed with ${service.getWSDLDocumentLocation.toString} [connectionTimeout: $connectionTimeout, receiveTimeout: $receiveTimeout]")
      service.getFlightInformationSoap match {
        case binder: BindingProvider =>
          binder.getRequestContext.put("javax.xml.ws.client.connectionTimeout", connectionTimeout.toString)
          binder.getRequestContext.put("javax.xml.ws.client.receiveTimeout", receiveTimeout.toString)
          if (!endPointUrl.isEmpty)
            binder.getRequestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endPointUrl)
          binder
        case flightInformationSoap => flightInformationSoap
      }
    } match {
      case Success(flightInformationSoap) => flightInformationSoap
      case Failure(t) =>
        log.error(s"Failed to start BHX feed: ${t.getMessage}", t)
        throw new RuntimeException("Failed to start BHX feed.", t)
    }
  }

}
