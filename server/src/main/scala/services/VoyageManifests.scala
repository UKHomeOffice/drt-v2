package services

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import drt.shared.DqEventCodes
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.util.Success

case class UpdateLatestZipFilename(filename: String)

case object GetLatestZipFilename

case class VoyageManifestState(manifests: Set[VoyageManifest], latestZipFilename: String)

object Manifests {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val actorSystem: ActorSystem = ActorSystem("AdvPaxInfo")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def fileNameAndContentFromZip[X](zipFileName: String,
                                   zippedFileByteStream: Source[ByteString, X],
                                   maybePort: Option[String],
                                   maybeAirlines: Option[List[String]]): Seq[(String, VoyageManifest)] = {
    val inputStream: InputStream = zippedFileByteStream.runWith(
      StreamConverters.asInputStream()
    )
    val zipInputStream = new ZipInputStream(inputStream)
    val vmStream = Stream
      .continually(zipInputStream.getNextEntry)
      .takeWhile(_ != null)
      .filter(jsonFile => jsonFile.getName.split("_")(4) == DqEventCodes.DepartureConfirmed)
      .filter {
        case _ if maybeAirlines.isEmpty => true
        case jsonFile => maybeAirlines.get.contains(jsonFile.getName.split("_")(3).take(2))
      }
      .map {
        case jsonFile =>
          val buffer = new Array[Byte](4096)
          val stringBuffer = new ArrayBuffer[Byte]()
          var len: Int = zipInputStream.read(buffer)

          while (len > 0) {
            stringBuffer ++= buffer.take(len)
            len = zipInputStream.read(buffer)
          }
          val content: String = new String(stringBuffer.toArray, UTF_8)
          val manifest = VoyageManifestParser.parseVoyagePassengerInfo(content)
          Tuple3(zipFileName, jsonFile.getName, manifest)
      }.collect {
      case (zipFilename, _, Success(vm)) if maybePort.isEmpty || vm.ArrivalPortCode == maybePort.get => (zipFilename, vm)
    }
    log.info(s"Finished processing $zipFileName")
    vmStream
  }
}