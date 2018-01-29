package services

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import actors.GetState
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskableActorRef
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete, StreamConverters}
import akka.util.{ByteString, Timeout}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.graphstages.Crunch

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}
import scala.util.matching.Regex

case class UpdateLatestZipFilename(filename: String)

case object GetLatestZipFilename

case class VoyageManifestState(manifests: Set[VoyageManifest], latestZipFilename: String)


case class VoyageManifestsProvider(bucketName: String, portCode: String, manifestsSource: SourceQueueWithComplete[VoyageManifests], voyageManifestsActor: ActorRef) {
  implicit val actorSystem: ActorSystem = ActorSystem("AdvPaxInfo")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  var manifestsState: Set[VoyageManifest] = Set()

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  val log: Logger = LoggerFactory.getLogger(getClass)

  def manifestsFuture(latestFile: String): Future[Seq[(String, VoyageManifest)]] = {
    log.info(s"Requesting zipFiles source for file names > ${latestFile.take(20)}")
    zipFiles(latestFile)
      .mapAsync(64) { filename =>
        log.info(s"Fetching $filename as stream")
        val zipByteStream = S3StreamBuilder(s3Client).getFileAsStream(bucketName, filename)
        Future(Manifests.fileNameAndContentFromZip(filename, zipByteStream, Option(portCode)))
      }
      .mapConcat(identity)
      .runWith(Sink.seq[(String, VoyageManifest)])
  }

  def start(): Unit = {
    val askableActor: AskableActorRef = voyageManifestsActor
    val futureVms = askableActor
      .ask(GetState)(new Timeout(5 minutes))
    futureVms
      .onComplete {
        case Success(something) =>
          something match {
            case VoyageManifestState(manifests, latestFilename) =>
              manifestsState = manifests
              log.info(s"Setting initial state with ${manifestsState.size} manifests, and offering to the manifests source")
              manifestsSource.offer(VoyageManifests(manifests))
              fetchAndPushManifests(latestFilename)
            case unexpected =>
              log.warn(s"Received unexpected ${unexpected.getClass}")
              fetchAndPushManifests("")
          }
        case Failure(t) =>
          log.warn(s"Didn't receive voyage manifest state from actor: $t")
          fetchAndPushManifests("")
      }
    futureVms.recover {
      case t =>
        log.warn(s"Didn't receive voyage manifest state from actor: $t")
        fetchAndPushManifests("")
    }
  }

  def fetchAndPushManifests(startingFilename: String): Unit = {
    log.info(s"Fetching manifests from files newer than $startingFilename")
    val vmFuture = manifestsFuture(startingFilename)
    val intervalSeconds = 60
    vmFuture.onComplete {
      case Success(ms) =>
        log.info(s"manifestsFuture Success")
        val nextFetchMaxFilename = updateState(startingFilename, ms)
        log.info(s"latestZipFilename: '$nextFetchMaxFilename'. ${manifestsState.size} manifests")
        voyageManifestsActor ! UpdateLatestZipFilename(nextFetchMaxFilename)
        voyageManifestsActor ! VoyageManifests(manifestsState)

        log.info(s"Waiting $intervalSeconds seconds before polling for more manifests")
        Thread.sleep(intervalSeconds * 1000)

        fetchAndPushManifests(nextFetchMaxFilename)
      case Failure(throwable) =>
        handleFailure(startingFilename, intervalSeconds, throwable)
    }
    vmFuture.recover {
      case throwable =>
        handleFailure(startingFilename, intervalSeconds, throwable)
    }
  }

  def updateState(startingFilename: String, ms: Seq[(String, VoyageManifest)]): String = {
    if (ms.nonEmpty) {
      val maxFilename = ms.map(_._1).max
      val vms = ms.map(_._2).toSet
      val newManifests = vms -- manifestsState
      if (newManifests.nonEmpty) {
        manifestsState = manifestsState ++ newManifests
        log.info(s"${newManifests.size} manifests offered")
        manifestsSource.offer(VoyageManifests(newManifests))
      } else {
        log.info(s"No new manifests")
      }
      maxFilename
    } else {
      log.info(s"No manifests received")
      startingFilename
    }
  }

  def handleFailure(startingFilename: String, intervalSeconds: Int, t: Throwable): Unit = {
    log.error(s"Failed to fetch manifests, trying again after $intervalSeconds seconds: $t")
    Thread.sleep(intervalSeconds * 1000)
    log.info(s"About to retry fetching new manifests")
    fetchAndPushManifests(startingFilename)
  }

  def zipFiles(latestFile: String): Source[String, NotUsed] = {
    filterToFilesNewerThan(filesAsSource, latestFile)
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

object Manifests {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val actorSystem: ActorSystem = ActorSystem("AdvPaxInfo")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def fileNameAndContentFromZip[X](zipFileName: String, zippedFileByteStream: Source[ByteString, X], maybePort: Option[String]): Seq[(String, VoyageManifest)] = {
    val inputStream: InputStream = zippedFileByteStream.runWith(
      StreamConverters.asInputStream()
    )
    val zipInputStream = new ZipInputStream(inputStream)
    val vmStream = Stream.continually(zipInputStream.getNextEntry).takeWhile(_ != null).map { jsonFile =>
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