package drt.server.feeds.api

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.mfglabs.commons.aws.s3._
import drt.server.feeds.api.S3ApiProvider.log
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.DqManifests
import services.SDate
import uk.gov.homeoffice.drt.arrivals.EventTypes
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


trait ApiProviderLike {
  def manifestAsStream(lastFileName: String): Source[DqManifests, NotUsed]
}

case class DqFileContent(fileName: String, manifests: Iterable[String])

trait DqFileContentProvider {
  def fromS3(objectKey: String): DqFileContent
}

case class DqFileContentProviderImpl(s3Client: AmazonS3Client, bucketName: String)
                                    (implicit mat: Materializer) extends DqFileContentProvider {
  override def fromS3(objectKey: String): DqFileContent = {
    val zipByteStream = s3Client.getFileAsStream(bucketName, objectKey)
    fileNameAndContentFromZip(objectKey, zipByteStream)
  }

  def fileNameAndContentFromZip(zipFileName: String, zippedFileByteStream: Source[ByteString, NotUsed])
                               (implicit mat: Materializer): DqFileContent = {
    val inputStream: InputStream = zippedFileByteStream
      .log(getClass.getName)
      .runWith(StreamConverters.asInputStream())

    val zipInputStream = new ZipInputStream(inputStream)

    val jsonContents = Try {
      Stream
        .continually(zipInputStream.getNextEntry)
        .takeWhile(_ != null)
        .map { zipEntry =>
          val buffer = new Array[Byte](4096)
          val stringBuffer = new ArrayBuffer[Byte]()
          var len: Int = zipInputStream.read(buffer)
          log.info(s"Zip: $zipFileName :: ${zipEntry.getName}")

          while (len > 0) {
            stringBuffer ++= buffer.take(len)
            len = zipInputStream.read(buffer)
          }
          log.debug(s"$zipFileName: ${zipEntry.getName}")
          new String(stringBuffer.toArray, UTF_8)
        }
        .toList
    } match {
      case Success(contents) => contents
      case Failure(e) =>
        log.error(e.getMessage)
        List.empty[String]
    }

    Try(zipInputStream.close())

    DqFileContent(zipFileName, jsonContents)
  }
}

trait DqFileNamesProvider {
  def fileNamesAfter(lastFileName: String): Source[String, NotUsed]
}

case class DqFileNamesProviderImpl(s3Client: AmazonS3Client, bucketName: String) extends DqFileNamesProvider {
  override def fileNamesAfter(lastFileName: String): Source[String, NotUsed] = {
    val filterFrom: String = filterFromFileName(lastFileName)
    s3Client.
      listFilesAsStream(bucketName)
      .collect {
        case s3ObjectSummary if filterFrom <= s3ObjectSummary.getKey => s3ObjectSummary.getKey
      }
  }

  def filterFromFileName(latestFile: String): String = {
    latestFile match {
      case S3ApiProvider.dqRegex(dateTime, _) => dateTime
      case _ => latestFile
    }
  }
}

case class S3ApiProvider(fileNamesProvider: DqFileNamesProvider, contentProvider: DqFileContentProvider, portCode: PortCode)
                        (implicit actorSystem: ActorSystem, materializer: Materializer) extends ApiProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def manifestAsStream(lastFileName: String): Source[DqManifests, NotUsed] = fileNamesProvider
    .fileNamesAfter(lastFileName)
    .map(contentProvider.fromS3)
    .map { fileContent =>
      val manifests = fileContent.manifests
        .map(jsonStringToManifest)
        .collect {
          case Some(manifest) => manifest
        }
      DqManifests(fileContent.fileName, manifests)
    }

  def jsonStringToManifest(content: String): Option[VoyageManifest] = {
    VoyageManifestParser.parseVoyagePassengerInfo(content) match {
      case Success(m) =>
        if (m.EventCode == EventTypes.DC && m.ArrivalPortCode == portCode) {
          log.info(s"Using ${m.EventCode} manifest for ${m.ArrivalPortCode} arrival ${m.flightCode}")
          Option(m)
        }
        else None
      case Failure(t) =>
        log.error(s"Failed to parse voyage manifest json", t)
        None
    }
  }


  //  def manifestsFuture(latestFile: String): Future[Seq[(String, String)]] = {
  //    log.info(s"Requesting DQ zip files > ${latestFile.take(20)}")
  //    fileNamesProvider.fileNamesAfter(latestFile)
  //      .map { filename =>
  //        log.info(s"Fetching $filename")
  //        contentProvider.fromS3(filename)
  //      }
  //      .map {
  //        case DqFileContent(zipFileName, maybeManifests) => maybeManifests.map(content => (zipFileName, content))
  //      }
  //      .mapConcat(identity)
  //      .log(getClass.getName)
  //      .runWith(Sink.seq[(String, String)])
  //  }
}

object S3ApiProvider {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  val dqDateTimeRegex: Regex = "drt_dq_([0-9]{2})([0-9]{2})([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2})_[0-9]{4}\\.zip".r

  def dateFromApiZipFilename(filename: String): Option[SDateLike] = filename match {
    case dqDateTimeRegex(year, month, day, hour, minute, _) =>
      Try(SDate(2000 + year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)).toOption
    case _ => None
  }

  def latestUnexpiredDqZipFilename(maybeLatestFilename: Option[String], now: () => SDateLike, expireAfterMillis: Int): String = maybeLatestFilename match {
    case Some(latestZip) => dateFromApiZipFilename(latestZip) match {
      case None =>
        defaultApiLatestZipFilename(now, expireAfterMillis)
      case Some(date) =>
        val expireAtMillis = now().millisSinceEpoch - expireAfterMillis
        if (date.millisSinceEpoch > expireAtMillis)
          latestZip
        else
          defaultApiLatestZipFilename(now, expireAfterMillis)
    }
    case None => defaultApiLatestZipFilename(now, expireAfterMillis)
  }

  def defaultApiLatestZipFilename(now: () => SDateLike, expireAfterMillis: Int): String = {
    val expireAt = now().addMillis(-1 * expireAfterMillis)
    val yymmddYesterday = f"${expireAt.getFullYear() - 2000}%02d${expireAt.getMonth()}%02d${expireAt.getDate()}%02d"
    val hhmmYesterday = f"${expireAt.getHours()}%02d${expireAt.getMinutes()}%02d${expireAt.getSeconds()}%02d"

    s"drt_dq_${yymmddYesterday}_${hhmmYesterday}"
  }

}
