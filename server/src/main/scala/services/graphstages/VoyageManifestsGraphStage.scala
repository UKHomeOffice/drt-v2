package services.graphstages

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream._
import akka.util.ByteString
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DqEventCodes
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedFailure, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.SDate

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case class DqManifests(lastSeenFileName: String, manifests: Set[VoyageManifest]) {
  def isEmpty: Boolean = manifests.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def length: Int = manifests.size

  def update(newLastSeenFileName: String, newManifests: Set[VoyageManifest]): DqManifests = {
    val mergedManifests = manifests ++ newManifests
    DqManifests(newLastSeenFileName, mergedManifests)
  }
}

class VoyageManifestsGraphStage(bucketName: String,
                                portCode: String,
                                initialLastSeenFileName: String,
                                minCheckIntervalMillis: MillisSinceEpoch = 30000)
                               (implicit actorSystem: ActorSystem,
                                materializer: Materializer) extends GraphStage[SourceShape[ManifestsFeedResponse]] {
  val out: Outlet[ManifestsFeedResponse] = Outlet("manifestsOut")
  override val shape: SourceShape[ManifestsFeedResponse] = SourceShape(out)

  val log: Logger = LoggerFactory.getLogger(getClass)

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  var maybeResponseToPush: Option[ManifestsFeedResponse] = None
  var lastSeenFileName: String = initialLastSeenFileName
  var lastFetchedMillis: MillisSinceEpoch = 0

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          fetchAndUpdateState()
          pushManifests()
        }
      })

      def fetchAndUpdateState(): Unit = {
        val nowMillis = SDate.now().millisSinceEpoch
        val millisElapsed = nowMillis - lastFetchedMillis
        if (millisElapsed < minCheckIntervalMillis) {
          val millisToSleep = minCheckIntervalMillis - millisElapsed
          log.info(s"Minimum check interval ${minCheckIntervalMillis}ms not yet reached. Sleeping for ${millisToSleep}ms")
          Thread.sleep(millisToSleep)
        }

        maybeResponseToPush = Option(fetchNewManifests(lastSeenFileName))
        lastFetchedMillis = nowMillis
      }

      def pushManifests(): Unit = {
        if (isAvailable(out)) {
          if (maybeResponseToPush.isEmpty) log.info(s"Nothing to push right now")

          maybeResponseToPush.foreach(responseToPush => {
            log.info(s"Pushing ${responseToPush.getClass}")
            push(out, responseToPush)
          })

          maybeResponseToPush = None
        }
      }
    }
  }

  def fetchNewManifests(startingFileName: String): ManifestsFeedResponse = {
    log.info(s"Fetching manifests from files newer than $startingFileName")
    val eventualFileNameAndManifests = manifestsFuture(startingFileName)
      .map(fetchedFilesAndManifests => {
        val (latestFileName, fetchedManifests) = if (fetchedFilesAndManifests.isEmpty) {
          (startingFileName, Set[Option[VoyageManifest]]())
        } else {
          val lastSeen = fetchedFilesAndManifests.map { case (fileName, _) => fileName }.max
          val manifests = fetchedFilesAndManifests.map { case (_, manifest) => manifest }.toSet
          (lastSeen, manifests)
        }
        (latestFileName, fetchedManifests)
      })

    Try {
      Await.result(eventualFileNameAndManifests, 30 minute)
    } match {
      case Success((latestFileName, maybeManifests)) =>
        log.info(s"Fetched ${maybeManifests.count(_.isDefined)} manifests up to file $latestFileName")
        lastSeenFileName = latestFileName
        ManifestsFeedSuccess(DqManifests(latestFileName, maybeManifests.flatten), SDate.now())
      case Failure(t) =>
        log.warn(s"Failed to fetch new manifests: ${t.getStackTrace}")
        ManifestsFeedFailure(t.toString, SDate.now())
    }
  }

  def manifestsFuture(latestFile: String): Future[Seq[(String, Option[VoyageManifest])]] = {
    log.info(s"Requesting DQ zip files > ${latestFile.take(20)}")
    zipFiles(latestFile)
      .mapAsync(64) { filename =>
        log.info(s"Fetching $filename")
        val zipByteStream = S3StreamBuilder(s3Client).getFileAsStream(bucketName, filename)
        Future(fileNameAndContentFromZip(filename, zipByteStream, portCode, None))
      }
      .mapConcat(identity)
      .runWith(Sink.seq[(String, Option[VoyageManifest])])
  }

  def zipFiles(latestFile: String): Source[String, NotUsed] = {
    filterToFilesNewerThan(filesAsSource, latestFile)
  }

  def fileNameAndContentFromZip[X](zipFileName: String,
                                   zippedFileByteStream: Source[ByteString, X],
                                   maybePort: String,
                                   maybeAirlines: Option[List[String]]): Seq[(String, Option[VoyageManifest])] = {
    val inputStream: InputStream = zippedFileByteStream.runWith(
      StreamConverters.asInputStream()
    )
    val zipInputStream = new ZipInputStream(inputStream)
    val unzippedFilesStream = Stream
      .continually(zipInputStream.getNextEntry)
      .takeWhile(_ != null)

    val dcCount = unzippedFilesStream.count(jsonFile => jsonFile.getName.split("_")(4) == DqEventCodes.DepartureConfirmed)
    val ciCount = unzippedFilesStream.count(jsonFile => jsonFile.getName.split("_")(4) == DqEventCodes.CheckIn)

    log.info(s"$zipFileName contains $dcCount DC and $ciCount CI messages")

    val maybeManifests = unzippedFilesStream.map(_ => {
      val buffer = new Array[Byte](4096)
      val stringBuffer = new ArrayBuffer[Byte]()
      var len: Int = zipInputStream.read(buffer)

      while (len > 0) {
        stringBuffer ++= buffer.take(len)
        len = zipInputStream.read(buffer)
      }
      val content: String = new String(stringBuffer.toArray, UTF_8)
      log.info(s"attempting to parse manifest: $content")
      val tryManifest = VoyageManifestParser.parseVoyagePassengerInfo(content)

      val maybeManifest: Option[VoyageManifest] = tryManifest match {
        case Success(m) =>
          log.info(s"SUCCESS: ${m.ArrivalPortCode}")
          if (m.ArrivalPortCode == maybePort && m.EventCode == DqEventCodes.DepartureConfirmed) {
            log.info(s"Taking ${m.EventCode} manifest for ${m.ArrivalPortCode} flight ${m.flightCode}")
            Option(m)
          } else {
            log.info(s"Ignoring ${m.EventCode} manifest for ${m.ArrivalPortCode}")
            None
          }
        case Failure(f) =>
          log.info(s"FAILURE: ${f.getMessage}")
          None
      }

      (zipFileName, maybeManifest)
    })

    maybeManifests
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
