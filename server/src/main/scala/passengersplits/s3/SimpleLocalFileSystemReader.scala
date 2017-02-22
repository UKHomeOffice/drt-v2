package passengersplits.s3

import java.io.{File, FileInputStream, FilenameFilter, InputStream}
import java.util.zip.ZipInputStream

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.event.LoggingAdapter
import akka.stream.{ActorMaterializer, ClosedShape, Materializer}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source, StreamConverters}
import passengersplits.core
import core.ZipUtils.UnzippedFileContent
import core.{Core, CoreActors, CoreLogging, ZipUtils}
import passengersplits.core.ZipUtils
import passengersplits.core.ZipUtils.UnzippedFileContent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait SimpleLocalFileSystemReader extends
  UnzippedFilesProvider
  with FilenameProvider
  with Core {

  implicit val flowMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def path: String
  private def file: File = new File(path)

  def allFilePaths: Iterator[String] = file.list(new FilenameFilter {
    def accept(dir: File, name: String) = {
      name.endsWith(".zip")
    }
  }).toIterator


  def latestFilePathsIterator: Iterator[String] = allFilePaths.filter(zipFileNameFilter(_))

  override def fileNameStream: Source[String, NotUsed] = Source.fromIterator(() => latestFilePathsIterator)

  def zipFilenameToEventualFileContent(zipFilename: String)(implicit actorMaterializer: Materializer, ec: ExecutionContext): Future[List[UnzippedFileContent]] = Future {
    val inputStream = new FileInputStream(new File(path + zipFilename))
    println(s"loading ${zipFilename}")
    val zipInputStream = new ZipInputStream(inputStream)
    val unzippedFileContent = ZipUtils.unzipAllFilesInStream(zipInputStream).map(_.copy(zipFilename = Some(zipFilename))).toList
    unzippedFileContent.map(_.copy(zipFilename = Some(zipFilename)))

  }


  override def unzippedFilesAsSource = fileNameStream.
    mapAsync(8) {
      //val streamAll: ActorRef = unzippedFilesAsSource.runWith(Sink.actorSubscriber(WorkerPool.props(flightPassengerReporter)))
      zipFilenameToEventualFileContent
    }.mapConcat(t=>t)
}


object FileSystemAkkaStreamReading {
  def testUnzipSink(log: LoggingAdapter)(runId: Int): Sink[String, Future[Done]] = Sink.foreach((fname: String) => {
    log.info(s"${runId} We'd unzip ${fname}")
    Thread.sleep(100)
    log.info(s"${runId} pretend zipping done ${fname}")
  })


  // The caller will probably want to await a promise that is completed from signalDone
  def runOnce[RD, RS](log: LoggingAdapter)(unzippedFileProvider: FilenameProvider)
                     (runId: Int,
                      signalDone: (Try[Done]) => Unit,
                      onNewFileSeen: (String) => Any,
                      unzipSink: Sink[String, RS])(implicit mat: Materializer): NotUsed = {
    val firstPathsSource: Source[String, NotUsed] = unzippedFileProvider.latestFilePaths

    val updateLatestSeen: Sink[String, Future[Done]] = Sink.foreach(onNewFileSeen(_))
    val unzippyThings: Sink[String, RS] = unzipSink

    val onCompleteSink: Sink[String, NotUsed] = Sink.onComplete(signalDone)
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val bcast = b.add(Broadcast[String](3))
      firstPathsSource ~> bcast.in
      bcast.out(0) ~> Flow[String].map(fname => {
        log.info(s"${runId} flow1")
        fname
      }) ~> updateLatestSeen
      bcast.out(1) ~> unzippyThings
      bcast.out(2) ~> onCompleteSink
      ClosedShape
    })

    log.info(s"runOnce ${runId}")
    g.run()
  }
}

class StatefulFilenameProvider(log: LoggingAdapter, var latestFileSeen: Option[String] = None) {
  val outerNewFileSeen = (newFilename: String) => {
    val newLatestFilename = latestFileSeen match {
      case None =>
        Some(newFilename)
      case Some(latestFile) =>
        Option(if (newFilename > latestFile) {
          log.info(s"Setting new latest filename ${newFilename}")
          newFilename
        } else latestFile)
    }

    latestFileSeen = newLatestFilename
  }

  val defaultFilenameFilter = (filename: String) => {
    val isBigger = latestFileSeen match {
      case Some(latestFile) =>
        filename > latestFile
      case None => true
    }
    isBigger
  }
}

case class StatefulLocalFileSystemPoller(initialLatestFile: Option[String] = None,
                                         zipFilePath: String)(implicit outersystem: ActorSystem) {
  val statefulPoller = new StatefulFilenameProvider(outersystem.log, initialLatestFile)

  import statefulPoller._

  val unzippedFileProvider = new SimpleLocalFileSystemReader {
    implicit def system = outersystem
    override def path = zipFilePath
    override def zipFileNameFilter(filename: String) = statefulPoller.defaultFilenameFilter(filename)
  }

  val onNewFileSeen = outerNewFileSeen
}

case class AtmosStatefulPoller(initialLatestFile: Option[String] = None)(implicit outersystem: ActorSystem) {
  val statefulPoller = new StatefulFilenameProvider(outersystem.log, initialLatestFile)

  val log = outersystem.log
  val unzippedFileProvider = new SimpleAtmosReader {
    implicit def system = outersystem

    import DqSettings.fnameprefix

    def log: LoggingAdapter = system.log

    override def zipFileNameFilter(filename: String) = statefulPoller.defaultFilenameFilter(filename)
  }
  val onNewFileSeen: (String) => Unit = statefulPoller.outerNewFileSeen
}
