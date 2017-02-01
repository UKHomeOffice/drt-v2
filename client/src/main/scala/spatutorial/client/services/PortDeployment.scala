package spatutorial.client.services

import diode.data.Pot
import spatutorial.client.services.HandyStuff._
import spatutorial.shared.FlightsApi._
import spatutorial.shared.{CrunchResult, MilliDate}
import spatutorial.client.logger._

import scala.collection.immutable.{IndexedSeq, Iterable, Map, Seq}

object PortDeployment {
  def portDeskRecs(portRecs: Map[TerminalName, Map[QueueName, Pot[PotCrunchResult]]]): List[(Long, List[(Int, String)])] = {
//    val x: List[Map[QueueName, Pot[PotCrunchResult]]] = portRecs.values.toList
    val seconds: Range = secondsRangeFromPortCrunchResult(portRecs.values.toList)
    val portRecsByTerminal: List[List[(Int, TerminalName)]] = portRecs.map {
      case (terminalName, queueRecs: Map[String, Seq[Int]]) =>
        val deskRecsByMinute: Iterable[IndexedSeq[Int]] = queueRecs.values.transpose((deskRecs: Pot[Pot[CrunchResult]]) => {
          for {
            crunchResultPot: Pot[CrunchResult] <- deskRecs
            crunchResult: CrunchResult <- crunchResultPot
          } yield crunchResult.recommendedDesks
        }).toList.flatten
        deskRecsByMinute.transpose.map((x: Iterable[Int]) => x.sum).map((_, terminalName)).toList
      case _ => List()
    }.toList
    log.info(s"untransposed: ${portRecsByTerminal}")
    log.info(s"transposed: ${portRecsByTerminal.transpose}")
    val d: List[(List[(Int, TerminalName)], Int)] = portRecsByTerminal.transpose.zip(seconds)
    val e: List[(Long, List[(Int, TerminalName)])] = d.map((y: (List[(Int, TerminalName)], Int)) => (y._2.toLong * 1000, y._1))
//    val e = d.map((x: (List[(Int, String)], Int)) => (x._2.toLong * 1000, x._1))
    //      .toList.zip(seconds).map((x: (List[(Int, String)], Int)) => (x._2.toLong * 1000, x._1))
    e
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

  def portDeployments(portDeskRecs: List[(Long, List[(Int, String)])]): List[(Long, List[(Int, String)])] = {
    //    for {
    //      minuteDeskRecs <- portDeskRecs
    //    } yield {
    //      (minuteDeskRecs._1, recsToDeployments(_.toInt)(minuteDeskRecs._2.map(_._1), 30).zip(minuteDeskRecs._2.map(_._2)).toList)
    //    }
    log.info(s"${portDeskRecs.length} portDeskRecs")
    portDeskRecs.map(minuteDeskRecs => {
      (minuteDeskRecs._1, recsToDeployments(_.toInt)(minuteDeskRecs._2.map(_._1), 30).zip(minuteDeskRecs._2.map(_._2)).toList)
    })
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

  def terminalStaffAvailable(deployments: List[(Long, List[(Int, TerminalName)])])(terminalName: String): (MilliDate) => Int = {
    val terminalDeployments: Map[Long, Int] = deployments.map(timeStaff => (timeStaff._1, timeStaff._2.find(_._2 == terminalName).map(_._1).getOrElse(0))).toMap
    (milliDate: MilliDate) => terminalDeployments.getOrElse(milliDate.millisSinceEpoch, 0)
  }
}