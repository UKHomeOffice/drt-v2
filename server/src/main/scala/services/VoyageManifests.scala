package services

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import actors.GetState
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
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import server.protobuf.messages.VoyageManifest._

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

case class VoyageManifestState(manifests: Set[VoyageManifest], latestZipFilename: String)

class VoyageManifestsActor extends PersistentActor with ActorLogging {
  var state = VoyageManifestState(
    manifests = Set(),
    latestZipFilename = defaultLatestZipFilename)

  def defaultLatestZipFilename: String = {
    val yesterday = SDate.now().addDays(-1)
    val yymmddYesterday = f"${yesterday.getFullYear() - 2000}%02d${yesterday.getMonth()}%02d${yesterday.getDate()}%02d"
    s"drt_dq_$yymmddYesterday"
  }

  val snapshotInterval = 100

  override def persistenceId: String = "VoyageManifests"

  override def receiveRecover: Receive = {
    case recoveredLZF: String =>
      log.info(s"Recovery received $recoveredLZF")
      state = state.copy(latestZipFilename = recoveredLZF)

    case m@VoyageManifestLatestFileNameMessage(_, Some(latestFilename)) =>
      log.info(s"Recovery received $m")
      state = state.copy(latestZipFilename = latestFilename)

    case VoyageManifestsMessage(_, manifestMessages) =>
      val updatedManifests = manifestMessages
        .map(voyageManifestFromMessage)
        .toSet -- state.manifests
      log.info(s"Recovery received ${updatedManifests.size} updated manifests")

      state = newStateFromManifests(state.manifests, updatedManifests)

    case SnapshotOffer(md, ss) =>
      log.info(s"Recovery received SnapshotOffer($md, $ss)")
      ss match {
        case VoyageManifestStateSnapshotMessage(Some(latestFilename), manifests) =>
          log.info(s"Updating state from VoyageManifestStateSnapshotMessage")
          val updatedStateWithManifests = newStateFromManifests(state.manifests, manifests.map(voyageManifestFromMessage).toSet)
          state = updatedStateWithManifests.copy(latestZipFilename = latestFilename)

        case lzf: String =>
          log.info(s"Updating state from latestZipFilename $lzf")
          state = state.copy(latestZipFilename = lzf)

        case u => log.info(s"Received unexpected snapshot data: $u")
      }

    case RecoveryCompleted =>
      log.info(s"Recovery completed")
  }

  def newStateFromManifests(existingManifests: Set[VoyageManifest], manifestUpdates: Set[VoyageManifest]): VoyageManifestState = {
    state.copy(manifests = existingManifests ++ manifestUpdates)
  }

  override def receiveCommand: Receive = {
    case UpdateLatestZipFilename(updatedLZF) if updatedLZF != state.latestZipFilename =>
      log.info(s"Received update - latest zip file is: $updatedLZF")
      state = state.copy(latestZipFilename = updatedLZF)

      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"Saving VoyageManifests snapshot ${state.latestZipFilename}, ${state.manifests.size} manifests")
        saveSnapshot(stateToMessage(state))
      } else persist(latestFilenameToMessage(state.latestZipFilename)) { lzf =>
        log.info(s"Persisting VoyageManifests latestZipFilename ${lzf.latestFilename.getOrElse("")}")
        context.system.eventStream.publish(lzf)
      }

    case UpdateLatestZipFilename(updatedLZF) if updatedLZF == state.latestZipFilename =>
      log.info(s"Received update - latest zip file is: $updatedLZF - no change")

    case VoyageManifests(updatedManifests) =>
      log.info(s"Received ${updatedManifests.size} updated manifests")

      state = newStateFromManifests(state.manifests, updatedManifests)

      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"Saving VoyageManifests snapshot ${state.latestZipFilename}, ${state.manifests.size} manifests")
        saveSnapshot(stateToMessage(state))
      } else persist(voyageManifestsToMessage(updatedManifests)) { vmm =>
        log.info(s"Persisting ${vmm.manifestMessages.size} manifest updates")
        context.system.eventStream.publish(vmm)
      }

    case GetState =>
      log.info(s"Being asked for state. Sending ${state.manifests.size} manifests and latest filename: ${state.latestZipFilename}")
      sender() ! state

    case GetLatestZipFilename =>
      log.info(s"Received GetLatestZipFilename request. Sending ${state.latestZipFilename}")
      sender() ! state.latestZipFilename

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.info(s"Received unexpected message $other")
  }

  private def voyageManifestsToMessage(updatedManifests: Set[VoyageManifest]) = {
    VoyageManifestsMessage(
      Option(SDate.now().millisSinceEpoch),
      updatedManifests.map(voyageManifestToMessage).toList
    )
  }

  def latestFilenameToMessage(filename: String): VoyageManifestLatestFileNameMessage = {
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now.millisSinceEpoch),
      latestFilename = Option(filename))
  }

  def passengerInfoToMessage(pi: PassengerInfoJson): PassengerInfoJsonMessage = {
    PassengerInfoJsonMessage(
      documentType = pi.DocumentType,
      documentIssuingCountryCode = Option(pi.DocumentIssuingCountryCode),
      eeaFlag = Option(pi.EEAFlag),
      age = pi.Age,
      disembarkationPortCode = pi.DisembarkationPortCode,
      inTransitFlag = Option(pi.InTransitFlag),
      disembarkationPortCountryCode = pi.DisembarkationPortCountryCode,
      nationalityCountryCode = pi.NationalityCountryCode
    )
  }

  def voyageManifestToMessage(vm: VoyageManifest): VoyageManifestMessage = {
    VoyageManifestMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      eventCode = Option(vm.EventCode),
      arrivalPortCode = Option(vm.ArrivalPortCode),
      departurePortCode = Option(vm.DeparturePortCode),
      voyageNumber = Option(vm.VoyageNumber),
      carrierCode = Option(vm.CarrierCode),
      scheduledDateOfArrival = Option(vm.ScheduledDateOfArrival),
      scheduledTimeOfArrival = Option(vm.ScheduledTimeOfArrival),
      passengerList = vm.PassengerList.map(passengerInfoToMessage)
    )
  }

  def stateToMessage(s: VoyageManifestState): VoyageManifestStateSnapshotMessage = {
    VoyageManifestStateSnapshotMessage(Option(s.latestZipFilename), stateVoyageManifestsToMessages(s.manifests))
  }

  def stateVoyageManifestsToMessages(manifests: Set[VoyageManifest]): Seq[VoyageManifestMessage] = {
    manifests.map(voyageManifestToMessage).toList
  }

  def passengerInfoFromMessage(m: PassengerInfoJsonMessage): PassengerInfoJson = {
    PassengerInfoJson(
      DocumentType = m.documentType,
      DocumentIssuingCountryCode = m.documentIssuingCountryCode.getOrElse(""),
      EEAFlag = m.eeaFlag.getOrElse(""),
      Age = m.age,
      DisembarkationPortCode = m.disembarkationPortCode,
      InTransitFlag = m.inTransitFlag.getOrElse(""),
      DisembarkationPortCountryCode = m.disembarkationPortCountryCode,
      NationalityCountryCode = m.nationalityCountryCode
    )
  }

  def voyageManifestFromMessage(m: VoyageManifestMessage): VoyageManifest = {
    VoyageManifest(
      EventCode = m.eventCode.getOrElse(""),
      ArrivalPortCode = m.arrivalPortCode.getOrElse(""),
      DeparturePortCode = m.departurePortCode.getOrElse(""),
      VoyageNumber = m.voyageNumber.getOrElse(""),
      CarrierCode = m.carrierCode.getOrElse(""),
      ScheduledDateOfArrival = m.scheduledDateOfArrival.getOrElse(""),
      ScheduledTimeOfArrival = m.scheduledTimeOfArrival.getOrElse(""),
      PassengerList = m.passengerList.toList.map(passengerInfoFromMessage)
    )
  }
}

case class VoyageManifestsProvider(s3HostName: String, bucketName: String, portCode: String, manifestsSource: SourceQueueWithComplete[VoyageManifests], voyageManifestsActor: ActorRef) {
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
        Future(fileNameAndContentFromZip(filename, zipByteStream))
      }
      .mapConcat(jsons => jsons)
      .runWith(Sink.seq[(String, VoyageManifest)])
  }

  def start(): Future[Unit] = {
    val askableActor: AskableActorRef = voyageManifestsActor
    askableActor.ask(GetState)(new Timeout(5 seconds)).map {
      case VoyageManifestState(manifests, latestFilename) =>
        manifestsState = manifests
        log.info(s"Setting initial state with ${manifestsState.size} manifests, and offering to the manifests source")
        manifestsSource.offer(VoyageManifests(manifests))
        fetchAndPushManifests(latestFilename)
    }
  }

  def fetchAndPushManifests(startingFilename: String): Unit = {
    log.info(s"Fetching manifests from files newer than $startingFilename")
    manifestsFuture(startingFilename).onSuccess {
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
          startingFilename
        }
        log.info("Waiting 1 minute before polling for more manifests")
        Thread.sleep(60000)
        log.info(s"Set latestZipFilename to '$nextFetchMaxFilename'")
        voyageManifestsActor ! UpdateLatestZipFilename(nextFetchMaxFilename)
        voyageManifestsActor ! VoyageManifests(manifestsState)

        fetchAndPushManifests(nextFetchMaxFilename)
    }
    manifestsFuture(startingFilename).onFailure {
      case t =>
        log.error(s"Failed to fetch manifests, trying again after 5 minutes: $t")
        Thread.sleep(300000)
        log.info(s"About to retry fetching new maniftests")
        fetchAndPushManifests(startingFilename)
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
