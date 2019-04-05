package manifests.queues

import manifests.passengers.PassengerTypeCalculator.log

import scala.io.Source
import scala.util.{Success, Try}

case class CarrierFastTrackSplit(iataCode: String, carrier: String, fastTrackSplit: Double)

object FastTrackFromCSV {

  lazy val fastTrackCarriers: Seq[CarrierFastTrackSplit] = loadFastTrack()

  def loadFastTrack(): Seq[CarrierFastTrackSplit] = {
    log.info(s"Loading fast track splits from CSV")
    val csvLines = Source
      .fromInputStream(getClass.getClassLoader.getResourceAsStream("lhr-fast-track.csv"))
      .getLines()
      .drop(1)

    csvLines.map(l => Try {
      val row = l.split(",")
      CarrierFastTrackSplit(row(0), row(1), row(2).toDouble)
    }).collect {
      case Success(s) => s
    }.toSeq
  }
}
