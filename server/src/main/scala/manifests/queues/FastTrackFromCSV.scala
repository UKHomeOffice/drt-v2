package manifests.queues

import drt.shared.CarrierCode
import org.slf4j.{Logger, LoggerFactory}

import scala.io.Source
import scala.util.{Success, Try}

case class CarrierFastTrackSplit(iataCode: CarrierCode, icaoCode: CarrierCode, fastTrackSplit: Double)

object FastTrackFromCSV {
  val log: Logger = LoggerFactory.getLogger(getClass)

  lazy val fastTrackCarriers: Seq[CarrierFastTrackSplit] = {
    log.info(s"Loading fast track splits from CSV")
    val csvLines = Source
      .fromInputStream(getClass.getClassLoader.getResourceAsStream("lhr-fast-track.csv"))
      .getLines()
      .drop(1)

    csvLines.map(l => Try {
      val row = l.split(",")
      CarrierFastTrackSplit(CarrierCode(row(0)), CarrierCode(row(1)), row(3).toDouble)
    }).collect {
      case Success(s) => s
    }.toSeq
  }
}
