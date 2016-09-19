package controllers

import java.nio.ByteBuffer

import akka.actor._
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Source, Sink}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.{ConfigFactory, Config}
import drt.chroma.StreamingChromaFlow
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.rabbit.JsonRabbit
import http.ProdSendAndReceive
import play.api.{Configuration, Environment}
import play.api.mvc._
import services.ApiService
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{ApiFlight, Api}
import spray.http._

//import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {
  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

  override def write[R: Pickler](r: R) = Pickle.intoBytes(r)
}

case object GetFlights

class FlightsActor extends Actor with ActorLogging {
  implicit val timeout = Timeout(5 seconds)
  val flights = mutable.Set[ApiFlight]()

  def receive = {
    case GetFlights =>
      log.info(s"Being asked for flights and I know about ${flights.size}")
      sender ! Flights(flights.toList)
    case Flights(fs) =>
      log.info(s"Adding ${fs.length} new flights")
      flights ++= fs
      log.info(s"Flights now ${flights.size}")
    case message => log.info("Actor saw" + message.toString)
  }
}

class Application @Inject()
(
  implicit val config: Configuration,
  implicit val mat: Materializer,
  env: Environment,
  system: ActorSystem,
  ec: ExecutionContext
)
  extends Controller {
  ctrl =>
  val flightsActor = system.actorOf(Props(classOf[FlightsActor]), "flightsActor")
  val flightsActorAskable: AskableActorRef = flightsActor
  val log = system.log

  val apiService = new ApiService {
    implicit val timeout = Timeout(5 seconds)

    override def getFlights(st: Long, end: Long): Future[List[ApiFlight]] = {
      val flights: Future[Any] = flightsActorAskable ? GetFlights
      val fsFuture = flights.collect {
        case Flights(fs) =>
          log.info(s"Got flights list ${fs}")
          fs
      }
      fsFuture
    }
  }

  val apiS: Api = apiService
  val mockStream = getClass.getClassLoader.getResourceAsStream("edi-chroma.json")
  val content = scala.io.Source.fromInputStream(mockStream).getLines().mkString("\n")

  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {

//    override def sendAndReceive = {
//      def res(req: HttpRequest): Future[HttpResponse] = Future {
//        log.info(s"request is ${req}")
//        req.uri.path match {
//          case Uri.Path("/edi/chroma/token") => {
//            HttpResponse().withEntity(
//              HttpEntity(ContentTypes.`application/json`,
//                """{"access_token":"LIk79Cj6NLssRcWePFxkJMIhpmSbe5gBGqOOxNIuxWNVd7JWsWtoOqAZDnM5zADvkbdIJ0BHkJgaya2pYyu8yH2qb8zwXA4TxZ0Jq0JwhgqulMgcv1ottnrUA1U61pu1TNFN5Bm08nvqZpYtwCWfGNGbxdrol-leZry_UD8tgxyZLfj45rgzmxm2u2DBN8TFpB_uG6Pb1B2XHM3py6HgYAmqSTjTK060PyNWTp_czsU",
//                  |"token_type":"bearer","expires_in":86399}""".stripMargin))
//          }
//          case Uri.Path("/edi/chroma/live/edi") => {
//            HttpResponse(StatusCodes.OK,
//              HttpEntity(ContentTypes.`application/json`,
//                content
//              ))
//          }
//        }
//      }
//      res
//    }

    implicit val system: ActorSystem = ctrl.system
  }

  val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromafetcher, 10 seconds)
  val ediMapping = JsonRabbit.ediMappingAndDiff(chromaFlow)

  def apiFlightCopy(ediMapping: Source[Seq[ChromaSingleFlight], Cancellable]) = {
    ediMapping.map(flights =>
      flights.map(flight =>
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
          SchDT = flight.SchDT)).toList)
  }

  val copiedToApiFlights = apiFlightCopy(ediMapping).map(Flights(_))
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
