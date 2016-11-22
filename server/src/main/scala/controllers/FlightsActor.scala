package controllers

import java.nio.ByteBuffer

import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.rabbit.JsonRabbit
import http.{ProdSendAndReceive, WithSendAndReceive}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import play.api.{Configuration, Environment}
import play.api.mvc._
import services.ApiService
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{Api, ApiFlight}
import spray.http._

import scala.language.postfixOps

//import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case object GetFlights


class FlightsActor(crunchActor: ActorRef) extends Actor with ActorLogging  with FlightState {
  implicit val timeout = Timeout(5 seconds)

  def receive = {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flights.size}")
      sender ! Flights(flights.values.toList)
    case Flights(newFlights) =>
      log.info(s"Adding ${newFlights.length} new flights")
      val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
      val lastMidnight = LocalDate.now().toString(formatter)
      onFlightUpdates(newFlights, AllInOneBucket.findFlightUpdates(lastMidnight, log))
      crunchActor ! CrunchFlightsChange(newFlights)
    case message => log.error("Actor saw unexpected message: " + message.toString)
  }
}
