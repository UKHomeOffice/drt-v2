package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.Inject
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.ChromaParserProtocol._
import drt.shared.Arrival
import org.slf4j.LoggerFactory
import play.api.mvc.{Action, Controller}
import play.api.{Configuration, Environment}
import services.SDate
import spray.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class Test @Inject()(implicit val config: Configuration,
                     implicit val mat: Materializer,
                     env: Environment,
                     val system: ActorSystem,
                     ec: ExecutionContext) extends Controller {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log = LoggerFactory.getLogger(getClass)

  val baseTime = SDate.now()

  def saveArrival(arrival: Arrival) = {
    system.actorSelection("akka://application/user/TestActor-LiveArrivals").resolveOne().map(actor => {

      actor ! arrival

    })
  }

  def addArrival() = Action {
    implicit request =>

      request.body.asJson.map(s => s.toString.parseJson.convertTo[ChromaLiveFlight]) match {
        case Some(flight) =>
          val walkTimeMinutes = 4
          val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
          val arrival = Arrival(
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
            rawICAO = flight.ICAO,
            rawIATA = flight.IATA,
            Origin = flight.Origin,
            SchDT = flight.SchDT,
            PcpTime = pcpTime,
            Scheduled = SDate(flight.SchDT).millisSinceEpoch
          )
          saveArrival(arrival)
          Created
        case None =>
          BadRequest(s"Unable to parse JSON: ${request.body.asText}")
      }
  }
}
