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
import org.slf4j.LoggerFactory
import play.api.{Configuration, Environment}
import play.api.mvc._
import services.ApiService
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{Api, ApiFlight, EdiAirportConfig, StnAirportConfig}
import spray.http._

import scala.language.postfixOps

//import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {
  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

  override def write[R: Pickler](r: R) = Pickle.intoBytes(r)
}

trait Core {
  def system: ActorSystem
}

trait SystemActors {
  self: Core =>
  val flightsActor = system.actorOf(Props(classOf[FlightsActor]), "flightsActor")
  val crunchActor = system.actorOf(Props(classOf[CrunchActor]), "crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor
}

object FeedStuff {

  val ArrivalsHall1 = "A1"
  val ArrivalsHall2 = "A2"
  val ediMapTerminals = Map(
    "T1" -> ArrivalsHall1,
    "T2" -> ArrivalsHall2
  )

  def ediBaggageTerminalHack(csf: ChromaSingleFlight) = {
    if (csf.BaggageReclaimId == "7") csf.copy(Terminal = ArrivalsHall2) else csf
  }

  def apiFlightCopy(ediMapping: Source[Seq[ChromaSingleFlight], Cancellable]) = {
    ediMapping.map(flights =>
      flights.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        ApiFlight(
          Operator = flight.Operator,
          Status = flight.Status, EstDT = flight.EstDT,
          ActDT = flight.ActDT, EstChoxDT = flight.EstChoxDT,
          ActChoxDT = flight.ActChoxDT,
          Gate = flight.Gate,
          Stand = flight.Stand,
          MaxPax = flight.MaxPax,
          ActPax = flight.ActPax,
          TranPax = flight.TranPax,
          RunwayID = flight.RunwayID,
          BaggageReclaimId = flight.BaggageReclaimId,
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          ICAO = flight.ICAO,
          IATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime
        )
      }).toList)
  }

  def ediFlights(log: LoggingAdapter, chromaFetcher: ChromaFetcher): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher, 10 seconds)

    def ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()).map(csfs =>
      csfs.map(ediBaggageTerminalHack(_)).map(csf => ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    apiFlightCopy(ediMapping)
  }

  def stnFlights(log: LoggingAdapter, chromaFetcher: ChromaFetcher): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher, 10 seconds)

    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()))
  }
}

class Application @Inject()(
                             implicit
                             val config: Configuration,
                             implicit val mat: Materializer,
                             env: Environment,
                             override val system: ActorSystem,
                             ec: ExecutionContext
                           )
  extends Controller with Core with SystemActors {
  ctrl =>

  val log = system.log
//  val chromafetcher = new ChromaFetcher with MockedChromaSendReceive {
//    implicit val system: ActorSystem = ctrl.system
//  }

  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }
  val portCode = sys.env.get("PORT_CODE")
  log.info(s"PORT_CODE::: ${portCode}")
  implicit val timeout = Timeout(5 seconds)

  def getFlightsImpl: Future[List[ApiFlight]] = {
    val flights: Future[Any] = flightsActorAskable ? GetFlights
    val fsFuture = flights.collect {
      case Flights(fs) => fs
    }
    fsFuture
  }

  val apiService = portCode match {
    case Some("EDI") =>
      log.info("^^^^^^^^^^^ Doing EDI config")
      new ApiService with EdiAirportConfig {
        override def getFlights(st: Long, end: Long): Future[List[ApiFlight]] = getFlightsImpl
      }
    case Some("STN") =>
      log.info("^^^^^^^^^^^ Doing STN config")
      new ApiService with StnAirportConfig {
        override def getFlights(st: Long, end: Long): Future[List[ApiFlight]] = getFlightsImpl
      }
  }

  val copiedToApiFlights = portCode match {
    case Some("EDI") =>
      FeedStuff.ediFlights(log, chromafetcher).map(Flights(_))
    case Some("STN") =>
      FeedStuff.stnFlights(log, chromafetcher).map(Flights(_))
  }

  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def autowireApi(path: String) = Action.async(parse.raw) {
    implicit request =>
      println(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route
      Router.route[Api](apiService)(
        autowire.Core.Request(path.split("/"), Unpickle[Map[String, ByteBuffer]].fromBytes(b.asByteBuffer))
      ).map(buffer => {
        val data = Array.ofDim[Byte](buffer.remaining())
        buffer.get(data)
        Ok(data)
      })
  }

  def logging = Action(parse.anyContent) {
    implicit request =>
      request.body.asJson.foreach { msg =>
        println(s"CLIENT - $msg")
      }
      Ok("")
  }
}
