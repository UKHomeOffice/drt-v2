package services

import java.util.{Date, UUID}

import org.slf4j.LoggerFactory
import spatutorial.shared._
import spatutorial.shared.FlightsApi._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Codec
import scala.util.Try


//  case class Row(id: Int, city: String, city2: String, country: String, code1: String, code2: String, loc1: Double,
//                 loc2: Double, elevation: Double,dkDouble: Double, dk: String, tz: String)


trait AirportToCountryLike {
  lazy val airportInfo: Seq[AirportInfo] = {
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
    }.toList
  }

  def stripQuotes(row1: String): String = {
    row1.substring(1, row1.length - 1)
  }

  def airportInfoByAirportCode(code: String) = Future(airportInfo.find(_.code == code))

}

object AirportToCountry extends AirportToCountryLike {

}

abstract class ApiService
  extends Api with WorkloadsService with FlightsService with AirportToCountryLike {

  val log = LoggerFactory.getLogger(getClass)
////  var todos: List[DeskRecTimeslot] = Nil

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${
      new Date
    }"
  }

////
////  override def getAllTodos(): List[DeskRecTimeslot] = {
////    // provide some fake Todos
////    //    Thread.sleep(3000)
////    println(s"Sending ${todos.size} Todo items")
////    todos
////  }
////
////
////  override def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot] = {
////    println("Setting all the todos on the server")
////    todos = items
////    todos
////  }
////
////  // update a Todo
////  override def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot] = {
////    // TODO, update database etc :)
////    if (todos.exists(_.id == item.id)) {
////      todos = todos.collect {
////        case i if i.id == item.id => item
////        case i => i
////      }
////      println(s"Todo item was updated: $item")
////    } else {
////      // add a new item
////      val newItem = item.copy(id = UUID.randomUUID().toString)
////      todos :+= newItem
////      println(s"Todo item was added: $newItem")
////    }
////    Thread.sleep(300)
////    todos
////  }
//
//  // delete a Todo
//  override def deleteTodo(itemId: String): List[DeskRecTimeslot] = {
//    println(s"Deleting item with id = $itemId")
//    Thread.sleep(300)
//    todos = todos.filterNot(_.id == itemId)
//    todos
//  }

  override def crunch(terminalName: TerminalName, queueName: String, workloads: List[Double]): CrunchResult = {
    println(s"Crunch requested for $terminalName, $queueName, ${workloads}")
    val repeat = List.fill[Int](workloads.length) _
    TryRenjin.crunch(workloads, repeat(2), repeat(25))
  }

  override def processWork(workloads: List[Double], desks: List[Int]): SimulationResult = {
    println(s"processWork")
    log.info(s"ProcessWork workloads ${workloads.take(200)}")
    val fulldesks: List[Int] = desks.flatMap(x => List.fill(15)(x))
    log.info(s"ProcessWork desks ${desks.take(200)}")
    TryRenjin.processWork(workloads, fulldesks)
  }

}
