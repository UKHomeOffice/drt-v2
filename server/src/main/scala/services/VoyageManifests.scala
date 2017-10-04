package services

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, ActorSystem}
import akka.pattern.AskableActorRef
import akka.persistence._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete, StreamConverters}
import akka.util.ByteString
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.S3ClientOptions
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import akka.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Success
import scala.util.matching.Regex
import scala.concurrent.duration._

case class UpdateLatestZipFilename(filename: String)

case object GetLatestZipFilename

class VoyageManifestsActor extends PersistentActor with ActorLogging {
  var latestZipFilename = defaultLatestZipFilename

  private def defaultLatestZipFilename = {
    val yesterday = SDate.now().addDays(-1)
    val yymmddYesterday = f"${yesterday.getFullYear() - 2000}%02d${yesterday.getMonth()}%02d${yesterday.getDate()}%02d"
    s"drt_dq_$yymmddYesterday"
  }

  val snapshotInterval = 100

  override def persistenceId: String = "VoyageManifests"

  override def receiveRecover: Receive = {
    case recoveredLZF: String =>
      log.info(s"Recovery received $recoveredLZF")
      latestZipFilename = recoveredLZF

    case SnapshotOffer(md, ss) =>
      log.info(s"Recovery received SnapshotOffer($md, $ss)")
      ss match {
        case lzf: String => latestZipFilename = lzf
        case u => log.info(s"Received unexpected snapshot data: $u")
      }

    case RecoveryCompleted =>
      log.info(s"Recovery completed")
  }

  override def receiveCommand: Receive = {
    case UpdateLatestZipFilename(updatedLZF) if updatedLZF != latestZipFilename =>
      log.info(s"Received update - latest zip file is: $updatedLZF")
      latestZipFilename = updatedLZF

      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"Saving VoyageManifests latestZipFilename snapshot $latestZipFilename")
        saveSnapshot(latestZipFilename)
      } else persist(latestZipFilename) { lzf =>
        log.info(s"Persisting VoyageManifests latestZipFilename $latestZipFilename")
        context.system.eventStream.publish(lzf)
      }
    case GetLatestZipFilename =>
      log.info(s"Received GetLatestZipFilename request. Sending $latestZipFilename")
      sender() ! latestZipFilename

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")
    case other =>
      log.info(s"Received unexpected message $other")
  }
}

case class VoyageManifestsProvider(s3HostName: String, bucketName: String, portCode: String, manifestsSource: SourceQueueWithComplete[VoyageManifests], fileNameActorStore: ActorRef) {
  implicit val actorSystem = ActorSystem("AdvPaxInfo")
  implicit val materializer = ActorMaterializer()

  var manifestsState: Set[VoyageManifest] = Set()

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  val log: Logger = LoggerFactory.getLogger(getClass)

  def manifestsFuture(latestFile: String): Future[Seq[(String, VoyageManifest)]] = {
    log.info(s"Requesting zipFiles source for file names > ${latestFile.take(20)}")
    zipFiles(latestFile)
      .mapAsync(64) { filename =>
        log.info(s"Fetching $filename as stream")
        val zipByteStream = S3StreamBuilder(s3Client).getFileAsStream(bucketName, filename)
        Future(fileNameAndContentFromZip(filename, zipByteStream))
      }
      .mapConcat(jsons => jsons)
      .runWith(Sink.seq[(String, VoyageManifest)])
  }

  def start() = {
    val askableFileNameActorStore: AskableActorRef = fileNameActorStore
    askableFileNameActorStore.ask(GetLatestZipFilename)(new Timeout(5 seconds)).map {
      case name: String => fetchAndPushManifests(name)
    }
  }

  def fetchAndPushManifests(lzf: String): Unit = {
    log.info(s"Fetching manifests from files newer than $lzf")
    val mf = manifestsFuture(lzf)
    var latestZipFilename = Option(lzf)
    mf.onSuccess {
      case ms =>
        log.info(s"manifestsFuture Success")
        val nextFetchMaxFilename = if (ms.nonEmpty) {
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
          lzf
        }
        log.info("Waiting 1 minute before polling for more manifests")
        Thread.sleep(60000)
        log.info(s"Set latestZipFilename to '$latestZipFilename'")
        fileNameActorStore ! UpdateLatestZipFilename(nextFetchMaxFilename)

        fetchAndPushManifests(nextFetchMaxFilename)
    }
    mf.onFailure {
      case t =>
        log.error(s"Failed to fetch manifests, trying again after 5 minutes: $t")
        Thread.sleep(300000)
        log.info(s"About to retry fetching new maniftests")
        fetchAndPushManifests(lzf)
    }
  }

  def fileNameAndContentFromZip(zipFileName: String, zippedFileByteStream: Source[ByteString, NotUsed]): Seq[(String, VoyageManifest)] = {
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
      case (zipFilename, _, Success(vm)) if vm.ArrivalPortCode == portCode => (zipFilename, vm)
    }
    log.info(s"Finished processing $zipFileName")
    vmStream
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

  def s3Client: AmazonS3AsyncClient = {
    val configuration: ClientConfiguration = new ClientConfiguration()
    configuration.setSignerOverride("S3SignerType")
    val provider: ProfileCredentialsProvider = new ProfileCredentialsProvider("drt-atmos")

    val client = new AmazonS3AsyncClient(provider, configuration)
    client.client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
    client.client.setEndpoint(s3HostName)
    client
  }
}
