package controllers

import actors.GetState
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch

import scala.language.implicitConversions
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait ShiftPersistence {
  implicit val timeout: Timeout = Timeout(250 milliseconds)

  val log = LoggerFactory.getLogger(getClass)

  def actorSystem: ActorSystem

  def shiftsActor: ActorRef

  def saveShifts(rawShifts: String) = {
      shiftsActor ! rawShifts
  }

  def getShifts(pointInTime: MillisSinceEpoch): Future[String] = {
    log.info(s"getShifts($pointInTime)")

    val shiftsFuture = shiftsActor ? GetState

    val shiftsCollected = shiftsFuture.collect {
      case shifts: String =>
        log.info(s"Shifts: Retrieved shifts from actor")
        val shiftLines = shifts.split("\n")
        val today = Crunch.getLocalLastMidnight(SDate.now())
        val twoDigitYear = today.getFullYear().toString.substring(2, 4)
        val filterDate2DigitYear = f"${today.getDate()}%02d/${today.getMonth()}%02d/$twoDigitYear"
        val filterDate4DigitYear = f"${today.getDate()}%02d/${today.getMonth()}%02d/${today.getFullYear()}"
        val todaysShifts = shiftLines.filter(l => {
          l.contains(filterDate2DigitYear) || l.contains(filterDate4DigitYear)
        })
        log.info(s"Shifts: Sending ${todaysShifts.length} shifts to frontend")
        todaysShifts.mkString("\n")
    }
    shiftsCollected
  }
}
