package services

import java.util.{UUID, Date}

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import spatutorial.shared._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.util.Random

abstract class ApiService
  extends Api with WorkloadsService with FlightsService {

  var todos: List[DeskRecTimeslot] = Nil

  override def welcomeMsg(name: String): String = {
    println("welcomeMsg")
    s"Welcome to SPA, $name! Time is now ${
      new Date
    }"
  }

  override def getAllTodos(): List[DeskRecTimeslot] = {
    // provide some fake Todos
//    Thread.sleep(3000)
    println(s"Sending ${ todos.size } Todo items")
    todos
  }


  override def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot] = {
    println("Setting all the todos on the server")
    todos = items
    todos
  }

  // update a Todo
  override def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot] = {
    // TODO, update database etc :)
    if (todos.exists(_.id == item.id)) {
      todos = todos.collect {
        case i if i.id == item.id => item
        case i => i
      }
      println(s"Todo item was updated: $item")
    } else {
      // add a new item
      val newItem = item.copy(id = UUID.randomUUID().toString)
      todos :+= newItem
      println(s"Todo item was added: $newItem")
    }
    Thread.sleep(300)
    todos
  }

  // delete a Todo
  override def deleteTodo(itemId: String): List[DeskRecTimeslot] = {
    println(s"Deleting item with id = $itemId")
    Thread.sleep(300)
    todos = todos.filterNot(_.id == itemId)
    todos
  }

  override def crunch(workloads: List[Double]): CrunchResult = {
    println(s"Crunch requested for ${workloads}")
    val repeat = List.fill[Int](workloads.length) _
    TryRenjin.crunch(workloads, repeat(2), repeat(25))
  }

  override def processWork(workloads: List[Double], desks: List[Int]): SimulationResult = {
    println(s"processWork")
    TryRenjin.processWork(workloads, desks.flatMap(x => List.fill(15)(x)))
  }
}
