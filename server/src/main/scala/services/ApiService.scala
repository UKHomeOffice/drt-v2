package services

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.{FixedPointPersistence, ShiftPersistence, StaffMovementsPersistence}
import drt.shared.CrunchApi.{CrunchState, CrunchUpdates, MillisSinceEpoch}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Codec
import scala.language.postfixOps
import scala.util.Try

trait AirportToCountryLike {
  lazy val airportInfo: Map[String, AirportInfo] = {
    val bufferedSource = scala.io.Source.fromURL(
      getClass.getResource("/airports.dat"))(Codec.UTF8)
    bufferedSource.getLines().map { l =>

      val t = Try {
        val splitRow: Array[String] = l.split(",")
        val sq: (String) => String = stripQuotes
        AirportInfo(sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)), sq(splitRow(4)))
      }
      t.getOrElse({
        AirportInfo("failed on", l, "boo", "ya")
      })
    }.map(ai => (ai.code, ai)).toMap
  }

  def stripQuotes(row1: String): String = {
    row1.substring(1, row1.length - 1)
  }

  def airportInfoByAirportCode(code: String) = Future(airportInfo.get(code))

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]] = Future {
    val res = codes.map(code => (code, airportInfo.get(code)))

    val successes: Set[(String, AirportInfo)] = res collect {
      case (code, Some(ai)) =>
        (code, ai)
    }

    successes.toMap
  }
}

object AirportToCountry extends AirportToCountryLike {

}

abstract class ApiService(val airportConfig: AirportConfig,
                          val shiftsActor: ActorRef,
                          val fixedPointsActor: ActorRef,
                          val staffMovementsActor: ActorRef)
  extends Api
    with AirportToCountryLike
    with ShiftPersistence
    with FixedPointPersistence
    with StaffMovementsPersistence {

  override implicit val timeout: akka.util.Timeout = Timeout(5 seconds)

  override val log: Logger = LoggerFactory.getLogger(this.getClass)

  def liveCrunchStateActor: AskableActorRef
  def forecastCrunchStateActor: AskableActorRef

  def actorSystem: ActorSystem

  def askableCacheActorRef: AskableActorRef

  def airportConfiguration(): AirportConfig = airportConfig

  def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]]
}

