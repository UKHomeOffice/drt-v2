package services

import java.util.{Date, UUID}

import org.slf4j.LoggerFactory
import services.workloadcalculator.PassengerQueueTypes
import spatutorial.shared._
import spatutorial.shared.FlightsApi._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Codec
import scala.util.Try
import spatutorial.shared.HasAirportConfig

//  case class Row(id: Int, city: String, city2: String, country: String, code1: String, code2: String, loc1: Double,
//                 loc2: Double, elevation: Double,dkDouble: Double, dk: String, tz: String)


trait AirportToCountryLike {
  lazy val airportInfo: Map[String, AirportInfo] = {
    val bufferedSource = scala.io.Source.fromURL(
      getClass.getResource("/airports.dat"))(Codec.UTF8)
    bufferedSource.getLines().map { l =>

      val t = Try {
        val splitRow: Array[String] = l.split(",")
        val sq: (String) => String = stripQuotes _
        AirportInfo(sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)), sq(splitRow(4)))
      }
      t.getOrElse({
        println(s"boo ${l}");
        AirportInfo("failed on", l, "boo", "ya")
      })
    }.map(ai => (ai.code, ai)).toMap
  }

  def stripQuotes(row1: String): String = {
    row1.substring(1, row1.length - 1)
  }

  def airportInfoByAirportCode(code: String) = Future(airportInfo.get(code))

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]] = Future {
    val res = codes.map(code =>
      (code, airportInfo.get(code))
    )
    val successes: Set[(String, AirportInfo)] = res collect {
      case (code, Some(ai)) =>
        (code, ai)
    }

    successes.toMap
  }
}

object AirportToCountry extends AirportToCountryLike {

}

abstract class ApiService
  extends Api with WorkloadsService with FlightsService with AirportToCountryLike {
  config: HasAirportConfig =>

  val log = LoggerFactory.getLogger(getClass)
  ////  var todos: List[DeskRecTimeslot] = Nil

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${
      new Date
    }"
  }

  override def crunch(terminalName: TerminalName, queueName: String, workloads: List[Double]): CrunchResult = {
    log.info(s"Crunch requested for $terminalName, $queueName, Workloads: ${workloads.take(15).mkString("(",",", ")")}...")
    val repeat = List.fill[Int](workloads.length) _
    val optimizerConfig = OptimizerConfig(airportConfigHolder.slaByQueue(queueName))
    //todo take the maximum desks from some durable store
    val minimumDesks: List[Int] = repeat(2)
    val maximumDesks: List[Int] = repeat(25)
    TryRenjin.crunch(workloads, minimumDesks, maximumDesks, optimizerConfig)
  }

  override def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult = {
    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x))

    val optimizerConfig = OptimizerConfig(airportConfigHolder.slaByQueue(queueName))
    TryRenjin.processWork(workloads, fulldesks, optimizerConfig)
  }

  def airportConfig: AirportConfig = {
    airportConfigHolder
  }
}
