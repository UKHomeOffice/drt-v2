package drt.client.services

import diode.data.Pot
import drt.client.services.HandyStuff._
import drt.shared.FlightsApi._
import drt.shared.{CrunchResult, MilliDate}
import drt.client.logger._

import scala.collection.immutable.{IndexedSeq, Iterable, Map, Seq}

object PortDeployment {
  def portDeskRecs(portRecs: Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]): List[(Long, List[(Int, TerminalName)])] = {
    val portRecsByTerminal: List[List[(Int, TerminalName)]] = portRecs.map {
      case (terminalName, queueRecs: Map[TerminalName, Seq[Int]]) =>
        val deskRecsByMinute: Iterable[IndexedSeq[Int]] = queueRecs.values.transpose((deskRecs: Pot[Pot[CrunchResult]]) => {
          for {
            crunchResultPot: Pot[CrunchResult] <- deskRecs
            crunchResult: CrunchResult <- crunchResultPot
          } yield crunchResult.recommendedDesks
        }).toList.flatten
        deskRecsByMinute.transpose.map((x: Iterable[Int]) => x.sum).map((_, terminalName)).toList
      case _ => List()
    }.toList
    val seconds: Range = secondsRangeFromPortCrunchResult(portRecs.values.toList)
    val portRecsByTimeInSeconds: List[(Int, List[(Int, TerminalName)])] = seconds.zip(portRecsByTerminal.transpose).toList
    val portRecsByTimeInMillis: List[(Long, List[(Int, TerminalName)])] = portRecsByTimeInSeconds.map {
      case (seconds, deskRecsWithTerminal) => (seconds.toLong * 1000, deskRecsWithTerminal)
    }
    portRecsByTimeInMillis
  }

  def secondsRangeFromPortCrunchResult(terminalRecs: List[Map[QueueName, Pot[PotCrunchResult]]]): Range = {
    val startMillis = for {
      queueRecs: Map[QueueName, Pot[PotCrunchResult]] <- terminalRecs
      firstQueueRecs = queueRecs.values
      crunchResultPotPot: Pot[PotCrunchResult] <- firstQueueRecs
      crunchResultPot: PotCrunchResult <- crunchResultPotPot
      crunchResult <- crunchResultPot
    } yield crunchResult.firstTimeMillis
    val firstSec = startMillis.headOption.getOrElse(0L) / 1000
    Range(firstSec.toInt, firstSec.toInt + (60 * 60 * 24), 60)
  }

  def terminalAutoDeployments(portDeskRecs: List[(Long, List[(Int, TerminalName)])], staffAvailable: MilliDate => Int): List[(Long, List[(Int, TerminalName)])] = {
    val roundToInt: (Double) => Int = _.toInt
    val deploymentsWithRounding = recsToDeployments(roundToInt) _
    portDeskRecs.map {
      case (millis, deskRecsWithTerminal) =>
        (millis, deploymentsWithRounding(deskRecsWithTerminal.map(_._1), staffAvailable(MilliDate(millis))).zip(deskRecsWithTerminal.map(_._2)).toList)
    }
  }

  def terminalDeployments(portDeskRecs: List[(Long, List[(Int, TerminalName)])], staffAvailable: (TerminalName, MilliDate) => Int): List[(Long, List[(Int, TerminalName)])] = {
    portDeskRecs.map {
      case (millis, deskRecsWithTerminal) =>
        val deploymentWithTerminal: List[(Int, TerminalName)] = deskRecsWithTerminal.map {
          case (_, terminalName) => staffAvailable(terminalName, MilliDate(millis))
        }.zip(deskRecsWithTerminal.map(_._2))

        (millis, deploymentWithTerminal)
    }
  }

  def recsToDeployments(round: Double => Int)(queueRecs: Seq[Int], staffAvailable: Int): Seq[Int] = {
    val totalStaffRec = queueRecs.sum
    queueRecs.foldLeft(List[Int]()) {
      case (agg, queueRec) if agg.length < queueRecs.length - 1 =>
        agg :+ round(staffAvailable * (queueRec.toDouble / totalStaffRec))
      case (agg, _) =>
        agg :+ staffAvailable - agg.sum
    }
  }

  def terminalStaffAvailable(deployments: List[(Long, List[(Int, TerminalName)])])(terminalName: TerminalName): (MilliDate) => Int = {
    val terminalDeployments: Map[Long, Int] = deployments.map(timeStaff => (timeStaff._1, timeStaff._2.find(_._2 == terminalName).map(_._1).getOrElse(0))).toMap
    (milliDate: MilliDate) => terminalDeployments.getOrElse(milliDate.millisSinceEpoch, 0)
  }
}
