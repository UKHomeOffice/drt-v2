package drt.server.feeds.api

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.Regions
import com.mfglabs.commons.aws.s3._
import com.typesafe.config.Config
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


trait ApiProviderLike {
  def manifestsFuture(latestFile: String): Future[Seq[(String, String)]]
}

case class S3ApiProvider(awsCredentials: AWSCredentials, bucketName: String)(implicit actorSystem: ActorSystem, materializer: Materializer) extends ApiProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val config: Config = actorSystem.settings.config

  def manifestsFuture(latestFile: String): Future[Seq[(String, String)]] = {
    log.info(s"Requesting DQ zip files > ${latestFile.take(20)}")
    zipFiles(latestFile)
      .mapAsync(64) { filename =>
        log.info(s"Fetching $filename")
        val zipByteStream = s3Client.getFileAsStream(bucketName, filename)
        Future(fileNameAndContentFromZip(filename, zipByteStream))
      }
      .map {
        case (zipFileName, maybeManifests) => maybeManifests.map(content => (zipFileName, content))
      }
      .mapConcat(identity)
      .runWith(Sink.seq[(String, String)])
  }

  def zipFiles(latestFile: String): Source[String, NotUsed] = {
    filterToFilesNewerThan(filesAsSource, latestFile)
  }

  def fileNameAndContentFromZip[X](zipFileName: String,
                                   zippedFileByteStream: Source[ByteString, X]): (String, List[String]) = {
    val inputStream: InputStream = zippedFileByteStream.runWith(
      StreamConverters.asInputStream()
    )
    val zipInputStream = new ZipInputStream(inputStream)

    val jsonContents = Try {
      Stream
        .continually(zipInputStream.getNextEntry)
        .takeWhile(_ != null)
        .map { _ =>
          val buffer = new Array[Byte](4096)
          val stringBuffer = new ArrayBuffer[Byte]()
          var len: Int = zipInputStream.read(buffer)

          while (len > 0) {
            stringBuffer ++= buffer.take(len)
            len = zipInputStream.read(buffer)
          }
          new String(stringBuffer.toArray, UTF_8)
        }
        .toList
    } match {
      case Success(contents) => contents
      case Failure(e) => log.error(e.getMessage)
                         List.empty[String]
    }

    Try(zipInputStream.close())

    (zipFileName, jsonContents)
  }

  def filterToFilesNewerThan(filesSource: Source[String, NotUsed], latestFile: String): Source[String, NotUsed] = {
    val filterFrom: String = filterFromFileName(latestFile)
    filesSource.filter(fn => fn >= filterFrom && fn != latestFile)
  }

  def filterFromFileName(latestFile: String): String = {
    latestFile match {
      case S3ApiProvider.dqRegex(dateTime, _) => dateTime
      case _ => latestFile
    }
  }

  def filesAsSource: Source[String, NotUsed] = s3Client.
    listFilesAsStream(bucketName)
    .map(_.getKey)

  def s3Client: AmazonS3Client = AmazonS3Client(Regions.EU_WEST_2, awsCredentials)()
}

object S3ApiProvider {
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
