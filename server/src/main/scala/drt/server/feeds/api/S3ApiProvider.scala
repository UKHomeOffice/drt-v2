package drt.server.feeds.api

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.matching.Regex


trait ApiProviderLike {
  def manifestsFuture(latestFile: String): Future[Seq[(String, String)]]
}

case class S3ApiProvider(bucketName: String)(implicit actorSystem: ActorSystem, materializer: Materializer) extends ApiProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  def manifestsFuture(latestFile: String): Future[Seq[(String, String)]] = {
    log.info(s"Requesting DQ zip files > ${latestFile.take(20)}")
    zipFiles(latestFile)
      .mapAsync(64) { filename =>
        log.info(s"Fetching $filename")
        val zipByteStream = S3StreamBuilder(s3Client).getFileAsStream(bucketName, filename)
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

    val jsonContents = Stream
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

    (zipFileName, jsonContents)
  }

  def filterToFilesNewerThan(filesSource: Source[String, NotUsed], latestFile: String): Source[String, NotUsed] = {
    val filterFrom: String = filterFromFileName(latestFile)
    filesSource.filter(fn => fn >= filterFrom && fn != latestFile)
  }

  def filterFromFileName(latestFile: String): String = {
    latestFile match {
      case dqRegex(dateTime, _) => dateTime
      case _ => latestFile
    }
  }

  def filesAsSource: Source[String, NotUsed] = {
    S3StreamBuilder(s3Client)
      .listFilesAsStream(bucketName)
      .map {
        case (filename, _) => filename
      }
  }

  def s3Client: AmazonS3AsyncClient = new AmazonS3AsyncClient(new ProfileCredentialsProvider("drt-prod-s3"))
}