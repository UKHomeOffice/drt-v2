package passengersplits.s3

import java.io.{InputStream, File => JFile}
import java.nio.file.{Path => JPath}
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.actor.{ActorSubscriber, ActorSubscriberMessage, MaxInFlightRequestStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source, StreamConverters}
import akka.{Done, NotUsed}
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.S3ClientOptions
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import passengersplits._
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.core.{Core, CoreActors, CoreLogging, ZipUtils}
import passengersplits.parsing.PassengerInfoParser
import drt.shared.PassengerSplits.VoyagePaxSplits
import drt.shared.SDateLike

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


trait UnzippedFilesProvider {
  def unzippedFilesAsSource: Source[UnzippedFileContent, NotUsed]
}

trait FilenameProvider {

  def fileNameStream: Source[String, NotUsed]

  def zipFileNameFilter(filename: String): Boolean

  def latestFilePaths = fileNameStream.filter(zipFileNameFilter)
}


trait S3Reader extends CoreLogging with UnzippedFilesProvider with FilenameProvider {

  def builder: S3StreamBuilder

  def createBuilder: S3StreamBuilder

  def bucket: String

  def createS3client: AmazonS3AsyncClient

  def numberOfCores = 1

  def unzipTimeout = FiniteDuration(400, TimeUnit.SECONDS)

  override def fileNameStream: Source[String, NotUsed] = builder.listFilesAsStream(bucket).map(_._1)

  def zipFilenameToEventualFileContent(zipFileName: String)(implicit actorMaterializer: Materializer, ec: ExecutionContext): Future[List[UnzippedFileContent]] = Future {
    try {
      log.info(s"Will parse ${zipFileName}")
      val threadSpecificBuilder = createBuilder
      val zippedByteStream = threadSpecificBuilder.getFileAsStream(bucket, zipFileName)
      val inputStream: InputStream = zippedByteStream.runWith(
        StreamConverters.asInputStream(unzipTimeout)
      )(actorMaterializer)

      ZipUtils.usingZip(new ZipInputStream(inputStream)) {
        unzippedStream =>
          val unzippedFileContent: List[UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(unzippedStream).toList
          unzippedFileContent.map(_.copy(zipFilename = Some(zipFileName)))
      }

    } catch {
      case e: Throwable =>
        log.error(e, s"Error in S3Poller for ${zipFileName}: ")
        throw e
    }
  }


  def intermediate(paths: Source[String, NotUsed])
                  (implicit actorMaterializer: Materializer, ec: ExecutionContext): Source[List[UnzippedFileContent], NotUsed] = {
    paths
      .mapAsync(numberOfCores) {
        zipFilenameToEventualFileContent
      }
  }

  def unzippedFilesSource(implicit actorMaterializer: Materializer, ec: ExecutionContext): Source[UnzippedFileContent, NotUsed] = {
    intermediate(latestFilePaths).mapConcat {
      t => t
    }
  }

}

trait SimpleS3Reader extends S3Reader with Core {
  this: CoreActors =>
  val bucket: String = "drt-deveu-west-1"

  def createBuilder = S3StreamBuilder(new AmazonS3AsyncClient())

  val builder = createBuilder

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val flowMaterializer = ActorMaterializer()


  lazy val unzippedFilesAsSource: Source[UnzippedFileContent, NotUsed] = unzippedFilesSource


  def streamAllThisToPrintln: Future[Done] = unzippedFilesAsSource.runWith(Sink.foreach(println))
}

object Decider {
  val decider: Supervision.Decider = {
    case _: java.io.IOException => Supervision.Restart
    case _ => Supervision.Stop
  }
}

object DqSettings {
  val fnameprefix = "drt_dq_"

}

trait SimpleAtmosReader extends S3Reader with Core {
  def bucket: String
  def skyscapeAtmosHost: String


  override def createBuilder: S3StreamBuilder = S3StreamBuilder(createS3client)

  override lazy val builder = createBuilder

  override def createS3client: AmazonS3AsyncClient = {
    val key = ""
    val prefix = ""
    val configuration: ClientConfiguration = new ClientConfiguration()
    configuration.setSignerOverride("S3SignerType")
    val provider: ProfileCredentialsProvider = new ProfileCredentialsProvider("drt-atmos")
    log.info("Creating S3 client")

    val client = new AmazonS3AsyncClient(provider, configuration)
    client.client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
    client.client.setEndpoint(skyscapeAtmosHost)
    client
  }

  implicit val flowMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  override lazy val unzippedFilesAsSource: Source[UnzippedFileContent, NotUsed] = unzippedFilesSource
}

trait UnzippedFilePublisher {
  self: SimpleAtmosReader =>
  def flightPassengerReporter: ActorRef

  def streamAllThis: ActorRef = unzippedFilesAsSource
    .runWith(Sink.actorSubscriber(WorkerPool.props(flightPassengerReporter)))
}

object PromiseSignals {
  def promisedDone = Promise[Done]()

}

object WorkerPool {

  case class Msg(id: Int, replyTo: ActorRef)

  case class Work(id: Int)

  case class Reply(id: Int)

  case class Done(id: Int)

  def props(passengerInfoRouter: ActorRef): Props = Props(new WorkerPool(passengerInfoRouter))
}

class WorkerPool(flightPassengerInfoRouter: ActorRef) extends ActorSubscriber with ActorLogging {

  import ActorSubscriberMessage._
  import PassengerInfoParser._

  val MaxQueueSize = 10
  var queue = Map.empty[Int, ActorRef]

  override val requestStrategy = new MaxInFlightRequestStrategy(max = MaxQueueSize) {
    override def inFlightInternally: Int = queue.size
  }

  def receive = {
    case OnNext(UnzippedFileContent(filename, content, _)) =>
      //      queue += sender
      // todo move this passengersplits.parsing to be part of the akka flow pipe? because then we can just apply a port level filter without akka stateful magic.
      // ln(s"Found a file content $filename")
      val parsed = VoyagePassengerInfoParser.parseVoyagePassengerInfo(content)
      log.info(s"flubflub ${parsed}")
      parsed match {
        case Success(voyagePassengerInfo) =>
          flightPassengerInfoRouter ! voyagePassengerInfo
        case Failure(f) =>
          log.error(f, s"Could not parse $content")
      }
    case OnNext(voyagePassengerInfo: VoyagePassengerInfo) =>
      flightPassengerInfoRouter ! voyagePassengerInfo
    case OnComplete =>
      log.info(s"WorkerPool OnComplete")
    case unknown =>
      log.error(s"WorkerPool got unknown ${unknown}")
  }


}

case class FlightId(flightNumber: String, carrier: String, schDateTime: SDateLike)

class SplitCalculatorWorkerPool extends ActorSubscriber with ActorLogging {

  import ActorSubscriberMessage._
  import PassengerInfoParser._

  val MaxQueueSize = 10
  var queue = Map.empty[Int, ActorRef]

  override val requestStrategy = new MaxInFlightRequestStrategy(max = MaxQueueSize) {
    override def inFlightInternally: Int = queue.size
  }


  var flightSplits: Map[FlightId, VoyagePaxSplits] = Map()

  def receive = {
    case OnNext(voyagePassengerInfo: VoyagePassengerInfo) =>
      val flightId = FlightId(voyagePassengerInfo.VoyageNumber, voyagePassengerInfo.CarrierCode, voyagePassengerInfo.scheduleArrivalDateTime.get)
    case OnComplete =>
      log.info(s"WorkerPool OnComplete")
    case unknown =>
      log.error(s"WorkerPool got unknown ${unknown}")
  }

}


object VoyagePassengerInfoParser {

  import PassengerInfoParser._
  import FlightPassengerInfoProtocol._
  import PassengerInfoParser._
  import spray.json._

  def parseVoyagePassengerInfo(content: String): Try[VoyagePassengerInfo] = {
    Try(content.parseJson.convertTo[VoyagePassengerInfo])
  }
}

//trait S3Actors {
//  self: Core =>
//  val s3PollingActor = system.actorOf(Props[])
//}
