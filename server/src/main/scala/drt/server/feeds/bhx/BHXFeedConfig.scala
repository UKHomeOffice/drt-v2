package drt.server.feeds.bhx

import javax.xml.ws.BindingProvider
import org.slf4j.Logger
import uk.co.bhx.online.flightinformation.{FlightInformation, FlightInformationSoap}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Try

trait BHXFeedConfig {
  val log: Logger
  val endPointUrl: String
  val connectionTimeout = 25000
  val receiveTimeout = 30000
  val pollFrequency: FiniteDuration = 4 minutes
  val initialDelayImmediately: FiniteDuration = 1 milliseconds

  lazy val serviceSoap: FlightInformationSoap =
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
    }.recoverWith { case t => log.error(s"Failure starting BHX feed: ${t.getMessage}", t); null }.get

  lazy val feed = BHXFeed(serviceSoap)

}