package passengersplits

import akka.{Done, NotUsed}
import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestActor.{AutoPilot, SetAutoPilot}
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import passengersplits.polling.{AtmosManifestFilePolling, FutureUtils}
import passengersplits.polling.AtmosManifestFilePolling.{BatchFileState, LoggingBatchFileState, TickId}
import services.mocklogger.MockLoggingLike
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import org.mockito.{ArgumentCaptor, ArgumentMatcher, Mockito}
import org.mockito.Matchers.argThat
import org.mockito.Mockito.{mock, verify, when}
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import passengersplits.core.PassengerInfoRouterActor.{ManifestZipFileInit, PassengerSplitsAck, VoyageManifestZipFileComplete, VoyageManifestZipFileCompleteAck}
import passengersplits.core.PassengerSplitsInfoByPortRouter
import passengersplits.s3.FileProvider

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent._
import scala.util.{Failure, Success, Try}
import scala.language.reflectiveCalls
// used for the FileProvider.callsToUnzippedFunc below, perhaps we should just use mockito?

class SimpleTestRouter(queue: mutable.Queue[VoyageManifest]) extends Actor {
  def receive: Receive = {
    case ManifestZipFileInit =>
      sender ! PassengerSplitsAck
    case vpi: VoyageManifest =>
      queue += vpi
      sender ! PassengerSplitsAck
    case VoyageManifestZipFileComplete(zipfilename, completionMonitor) =>
      completionMonitor ! VoyageManifestZipFileCompleteAck(zipfilename)
  }
}

object SimpleTestRouter {
  /*
   This defaultReceive is a simple stub implementing the interface of  PassengerSplitsInfoByPortRouter, complete with the
   individual acks, and batch acks needed for Akka Streams to detect and respond to back-pressure.
   */
  val log = LoggerFactory.getLogger(getClass())

  def defaultReceive(sender: ActorRef): PartialFunction[Any, Any] = {
    case ManifestZipFileInit =>
      sender ! PassengerSplitsAck
    case vpi: VoyageManifest =>
      sender ! PassengerSplitsAck
    case VoyageManifestZipFileComplete(zipfilename, completionMonitor) =>
      log.info(s"SimpleTestRouter autopilot got FlightPaxSplitBatchComplete $completionMonitor")
      completionMonitor ! VoyageManifestZipFileCompleteAck(zipfilename)
  }

}


class WhenUnzippingOrderMattersSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {
  implicit val materializer = ActorMaterializer()

  val batchAtMost: FiniteDuration = 3 seconds

  "Given one zip file " >> {
    "the latest filename should not be updated until the actor has received (acked) all the VoyagePaxInfo messages from the zipfile" >> {
      println("order of messages")
      val twoTicks = Source(List(new DateTime(2017, 1, 1, 12, 33, 20)))
      val fileProvider = new FileProvider {
        def createFilenameSource(): Source[String, NotUsed] = Source(List("drt_dq_170411_104441_4850.zip"))

        var callsToUnzippedContent = 0

        def zipFilenameToEventualFileContent(zipFileName: String) = {
          callsToUnzippedContent += 1

          Future {
            Thread.sleep(1000) /// take longer than we need, but not too long
            UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
              """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil
          }
        }

      }

      val testQueue = mutable.Queue[VoyageManifest]()

      val flightPassengerSplitReporter = system.actorOf(Props(classOf[SimpleTestRouter], testQueue), name = "flight-pax-reporter")

      val batchFileState = new SignallingBatchFileState("", "drt_dq_170411_104441_4850.zip" :: Nil) {
        override def onZipFileProcessed(filename: String): Unit = {
          system.log.info(s"onZipFileProcess ${filename}")
          assert(testQueue.length == 1)
          system.log.info(s"onZipFileProcess ${filename} got msg")

          super.onZipFileProcessed(filename)
        }
      }

      val pollingFuture = AtmosManifestFilePolling.beginPollingImpl(flightPassengerSplitReporter,
        twoTicks,
        fileProvider,
        batchFileState,
        batchAtMost
      )

      val expectedZipFile = "drt_dq_170411_104441_4850.zip"
      val expectedNumberOfCalls = 1

      pollingFuture.onFailure {
        case f: Throwable => {
          system.log.error(f, "failure on outer batches")
          failure
        }
      }
      val futureOfAllBatches = Future.sequence(List(batchFileState.onZipFileProcessCalled("drt_dq_170411_104441_4850.zip"), pollingFuture))

      (Await.result(futureOfAllBatches, 40 seconds), fileProvider.callsToUnzippedContent) === (List(expectedZipFile, Done), expectedNumberOfCalls)
    }
  }

}

class WhenUnzippingIfJsonIsBadSpec extends
  TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty()))
  with SpecificationLike with MockLoggingLike {

  implicit val materializer = ActorMaterializer()

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")
  val batchAtMost = 3 seconds

  "Given a zip file with a bad json " >> {
    "we should log the error, and process the next file" >> {
      system.log.info("unzipping is bad")
      val batchTick = Source(List(new DateTime(2017, 1, 1, 12, 33, 20)))

      val fileProvider = new FileProvider {
        def createFilenameSource(): Source[String, NotUsed] = Source(List(
          "drt_dq_170411_104441_4850.zip"
        ))

        var callsToUnzippedContent = 0

        def zipFilenameToEventualFileContent(zipFileName: String) = {
          callsToUnzippedContent += 1

          Future {
            Thread.sleep(1000) /// take longer than we need, but not too long
            UnzippedFileContent("drt_150302_060000_BA1234_DC_7890.json",
              """ this is not a valid json!!!""", Option("drt_dq_170411_104441_4850.zip")) ::
              UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
                """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil
          }
        }

      }
      val batchFileState = SignallingBatchFileState("", "drt_dq_170411_104441_4850.zip" :: Nil)

      withMockAppender { mockAppender =>
        val pollingFuture = AtmosManifestFilePolling.beginPollingImpl(flightPassengerSplitReporter,
          batchTick,
          fileProvider,
          batchFileState,
          batchAtMost
        )

        val expectedZipFile = "drt_dq_170411_104441_4850.zip"
        val exp = 1

        pollingFuture.onFailure {
          case f: Throwable => {
            system.log.error(f, "failure on outer batches")
            failure
          }
        }
        val futureOfAllBatches = Future.sequence(List(batchFileState.onZipFileProcessCalled("drt_dq_170411_104441_4850.zip"), pollingFuture))

        (Await.result(futureOfAllBatches, 10 seconds), fileProvider.callsToUnzippedContent) === (List(expectedZipFile, Done), exp)
        val argumentCaptor = ArgumentCaptor.forClass(classOf[ILoggingEvent])
        verify(mockAppender, Mockito.atLeastOnce()).doAppend(argumentCaptor.capture())

        val loggingCalls: List[ILoggingEvent] = argumentCaptor.getAllValues().asScala.toList

        val expectedMessage = """Failed to parse voyage passenger info: "drt_150302_060000_BA1234_DC_7890.json""""
        loggingCalls.exists(le => le.getLevel() == Level.WARN && le.getFormattedMessage().contains(expectedMessage))
      }
    }
  }

}

class WhenUnzippingIfEntireZipfileIsBad extends
  TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty()))
  with SpecificationLike with MockLoggingLike {
  implicit val materializer = ActorMaterializer()

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")
  val batchAtMost = 10 seconds


  "Given a zip file where unzipping fails" >> {
    "if it fails before any jsons are unzipped, we should log the error, and proceed to the next zip" >> {
      "we should log the error, and process the next file" >> {
        system.log.info("unzipping is bad")
        val tickId = new DateTime(2017, 1, 1, 12, 33, 20)
        val batchTick = Source(List(tickId))

        val fileProvider = new FileProvider {
          def createFilenameSource(): Source[String, NotUsed] = Source(List(
            "drt_dq_170411_104441_4850.zip",
            "drt_dq_180411_123821_9999.zip"
          ))


          def zipFilenameToEventualFileContent(zipFileName: String) = {
            callsToUnzippedContent += 1
            system.log.info(s"unzippedContentFunc $zipFileName $callsToUnzippedContent")
            callsToUnzippedContent match {
              case 1 =>
                Future.failed(new Exception(s"Some exception while unzipping $zipFileName"))
              case 2 => Future.successful(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
                """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil)

            }
          }

          var callsToUnzippedContent = 0

        }


        val batchFileState = new SignallingBatchFileState("", List(
          "drt_dq_170411_104441_4850.zip",
          "drt_dq_180411_123821_9999.zip"
        )) {
          private val promise = Promise[TickId]()
          val onBatchCompleteWasCalled = promise.future

          override def onBatchComplete(tickId: TickId): Unit = {
            super.onBatchComplete(tickId)
            promise.complete(Try(tickId))
          }
        }

        withMockAppender { mockAppender =>
          //system under test - AtmosFilePolling.beginPollingImpl
          val pollingFuture = AtmosManifestFilePolling.beginPollingImpl(flightPassengerSplitReporter,
            batchTick,
            fileProvider,
            batchFileState,
            batchAtMost
          )


          val expectedZipFile = "drt_dq_180411_123821_9999.zip"
          val expectedNumberOfCallsToUnzipContent = 2

          pollingFuture.onFailure {
            case f: Throwable => {
              system.log.error(f, "failure on processing zip file")
              failure
            }
          }
          pollingFuture.onComplete { case oc => system.log.info(s"pollingFuture complete with $oc") }
          batchFileState.onBatchCompleteWasCalled.onComplete { case oc => system.log.info(s"batchFileState.onBatchCompleteWasCalled complete with $oc") }

          val futureOfAllBatches = FutureUtils.waitAll(List(batchFileState.onBatchCompleteWasCalled, pollingFuture))

          (Await.result(futureOfAllBatches, 12 seconds), fileProvider.callsToUnzippedContent) === (List(Success(tickId), Success(Done)), expectedNumberOfCallsToUnzipContent)
          val argumentCaptor = ArgumentCaptor.forClass(classOf[ILoggingEvent])
          verify(mockAppender, Mockito.atLeastOnce()).doAppend(argumentCaptor.capture())

          val loggingCalls: List[ILoggingEvent] = argumentCaptor.getAllValues().asScala.toList

          val expectedMessage = """tickId: 2017-01-01T12:33:20.000Z, zip: "drt_dq_170411_104441_4850.zip" error in batch"""
          loggingCalls.exists(le => le.getLevel() == Level.WARN && le.getFormattedMessage().contains(expectedMessage))
        }
      }
    }
  }
}

case class SignallingBatchFileState(initialFilename: String, expectedZipFiles: Seq[String]) extends LoggingBatchFileState {
  private val promiseZipDone: Map[String, Promise[String]] = expectedZipFiles.map(fn => (fn, Promise[String]())).toMap

  def onZipFileProcessCalled(fn: String): Future[String] = promiseZipDone(fn).future

  override var latestFileName: String = initialFilename

  override def onZipFileProcessed(filename: String): Unit = {
    super.onZipFileProcessed(filename)
    promiseZipDone(filename).complete(Try(filename))
  }


  override def onBatchComplete(tickId: TickId): Unit = {
    super.onBatchComplete(tickId)
  }
}

class AtmosFileUnzipperSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential

  implicit val materializer = ActorMaterializer()

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")


  val outerSystem = system
  val batchAtMost = 2 seconds

  "Running multiple batches " >> {

    "If a batch is already running, when we tick again that tick is ignored, unzippedCalls count should be 1" >> {
      println("suppress second batch test")
      val tickIds = new DateTime(2017, 1, 1, 12, 33, 20) :: new DateTime(2017, 1, 1, 12, 34, 20) :: Nil

      val twoTicks = Source(tickIds)

      val fileProvider = new FileProvider {
        def createFilenameSource(): Source[String, NotUsed] = Source(List("drt_dq_170411_104441_4850.zip"))

        def zipFilenameToEventualFileContent(zipFileName: String) = {
          callsToUnzippedContent += 1

          Future {
            Thread.sleep(1000) /// take longer than we need, but not too long
            UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
              """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil
          }
        }

        var callsToUnzippedContent = 0
      }
      val batchFileState = new SignallingBatchFileState("", "drt_dq_170411_104441_4850.zip" :: Nil) {
        private val promiseBatchDone = tickIds.map(tid => (tid, Promise[TickId]())).toMap

        def onBatchCompleteWasCalled(tickId: TickId): Future[TickId] = promiseBatchDone(tickId).future

        override def onBatchComplete(tickId: TickId): Unit = {
          promiseBatchDone(tickId).complete(Try(tickId))
          super.onBatchComplete(tickId)
        }
      }

      AtmosManifestFilePolling.beginPollingImpl(flightPassengerSplitReporter,
        twoTicks,
        fileProvider,
        batchFileState,
        batchAtMost
      )

      val expectedZipFile = "drt_dq_170411_104441_4850.zip"
      val expectedNumberOfCalls = 1

      (Await.result(batchFileState.onZipFileProcessCalled("drt_dq_170411_104441_4850.zip"), 2 second), fileProvider.callsToUnzippedContent) === (expectedZipFile, expectedNumberOfCalls)
    }
  }
}

class WhenABatchTimesOut extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential

  implicit val materializer = ActorMaterializer()

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")


  val outerSystem = system
  val batchAtMost = 2 seconds

  """
    |If a batch processing time exceed the expected maximum time for a batch
    |  then the first should be failed, and logged
    |  and the subsequent tick allowed to pass
    |
    """.stripMargin >> {
    println("first batch times out, second works")
    val twoTicks = Source.tick(1 milli, 2000 milli, NotUsed).map(x => DateTime.now()).take(2)

    val fileProvider = new FileProvider {
      val mutableQueueOfFilenames = mutable.Queue(
        Source(List("drt_dq_170411_104441_4850.zip")),
        Source(List("drt_dq_170411_113331_4567.zip"))
      )


      def createFilenameSource(): Source[String, NotUsed] = mutableQueueOfFilenames.dequeue()

      val contents = UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
        """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil

      var callsToUnzippedContent = 0


      def zipFilenameToEventualFileContent(zipFileName: String): Future[List[UnzippedFileContent]] = {
        callsToUnzippedContent += 1
        system.log.info(s"unzipping $zipFileName $callsToUnzippedContent")
        callsToUnzippedContent match {
          case 1 =>
            Future.failed(new TimeoutException("fail with timeout"))
          case 2 =>
            Future {
              Thread.sleep(100) /// faster this time
              contents
            }
        }
      }

    }
    val batchFileState = SignallingBatchFileState("", "drt_dq_170411_104441_4850.zip" :: "drt_dq_170411_113331_4567.zip" :: Nil)

    val batchAtMost = 200 millis

    AtmosManifestFilePolling.beginPollingImpl(flightPassengerSplitReporter,
      twoTicks, fileProvider,
      batchFileState,
      batchAtMost
    )
    val FirstZip = "drt_dq_170411_104441_4850.zip"
    val SecondZip = "drt_dq_170411_113331_4567.zip"
    val expectedNumberOfCallsToUnzip = 1
    val result = (
      Try(Await.result(batchFileState.onZipFileProcessCalled(FirstZip), 500 milli)),
      Try(Await.result(batchFileState.onZipFileProcessCalled("drt_dq_170411_113331_4567.zip"), 10000 milli)),
      fileProvider.callsToUnzippedContent, batchFileState.latestFileName)
    system.log.info(s"what we got $result")
    result match {
      case (Success(FirstZip), Success(SecondZip), expectedNumberOfCalls, SecondZip) => true
      case actual =>
        system.log.error(s"match error: $actual")
        false
    }
  }

}

class AtmosFileUnzipperSingleZipSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential
  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")

  implicit val materializer = ActorMaterializer()

  val outerSystem = system


  "Processing a single zip fle " >> {
    "given a single zip file, we can unzip it and let the actor know about it" >> {

      val listOfZips = List("drt_dq_170411_104441_4850.zip")

      val calledBatchComplete: scala.collection.mutable.MutableList[String] = mutable.MutableList[String]()

      val batchFileState = SignallingBatchFileState("", listOfZips)


      AtmosManifestFilePolling.processAllZipFiles(DateTime.now(),
        (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil),
        flightPassengerSplitReporter, batchFileState, listOfZips
      )
      Thread.sleep(1000)
      true
    }

  }
}


class TestSourceConcat extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {
  implicit val mat = ActorMaterializer()
  "test we can bookend a stream" >> {
    val zips = Source(List("file1", "file2"))
    val unzipped: Map[String, Source[String, NotUsed]] = Map("file1" ->
      Source(List("abc", "def")),
      "file2" ->
        Source(List("123", "456"))
    )

    val g: Source[String, NotUsed] = zips.flatMapConcat(fn =>
      Source(List(s"end: ${fn}")).prepend(unzipped(fn).prepend(Source(List(s"begin: ${fn}"))))
    )

    val result: Future[Seq[String]] = g.runWith(Sink.seq)

    Await.result(result, 10 seconds) === Seq(
      "begin: file1",
      "abc",
      "def",
      "end: file1",
      "begin: file2",
      "123",
      "456",
      "end: file2"
    )
  }
//  "test we can bookend a stream" >> {
//    val zips = Source(List("file1", "file2"))
//    val unzippedAndSentToActor: Map[String, Future[List[String]]] = Map("file1" ->
//      Future(List("abc", "def")),
//      "file2" ->
//        Future(List("123", "456"))
//    )
//
//    val g: Source[String, NotUsed] = zips.flatMapConcat(fn => {
//      val unzipped1 = Await.result(unzippedAndSentToActor(fn), 3 seconds)
//      Source(List(s"end: ${fn}")).prepend(unzipped1).prepend(Source(List(s"begin: ${fn}")))
//    })
//    )
//
//    val result: Future[Seq[String]] = g.runWith(Sink.seq)
//
//    Await.result(result, 10 seconds) === Seq(
//      "begin: file1",
//      "abc",
//      "def",
//      "end: file1",
//      "begin: file2",
//      "123",
//      "456",
//      "end: file2"
//    )
//  }
}

class AtmosFileUnzipperMultipleZipSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential
  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")

  implicit val materializer = ActorMaterializer()

  val outerSystem = system


  "Processing two zip files " >> {
    "the first should be processed to completion before the second is started" >> {

      val zipFile1 = "drt_dq_170411_104441_4850.zip"
      val zipFile2 = "drt_dq_170411_125551_5687.zip"
      val listOfZips = List(zipFile1, zipFile2)

      val batchFileState = new BatchFileState {
        var messages: mutable.MutableList[String] = mutable.MutableList[String]()

        def latestFile: String = ""


        def onZipFileBegins(filename: String): Unit =
          messages += s"begin: $filename"

        def onZipFileProcessed(filename: String): Unit =
          messages += s"done: $filename"

        def onBatchComplete(tickId: TickId): Unit = ???
      }

      val unzippedContents = Map(
        zipFile1 -> Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option(zipFile1)) :: Nil),
        zipFile2 -> Future(UnzippedFileContent("drt_150302_060000_BA1234_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_BA1234_DC_4089"}
          """.stripMargin, Option(zipFile2)) :: Nil)
      )

      val allZips = AtmosManifestFilePolling.processAllZipFiles(new DateTime(2017, 1, 1, 12, 23),
        (zipfilename) => unzippedContents(zipfilename),
        flightPassengerSplitReporter, batchFileState, listOfZips
      )
      val allDone = Future.sequence(allZips)
      Await.ready(allDone, 3 seconds)

      batchFileState.messages.toList === List(
        "begin: " + zipFile1,
        "done: " + zipFile1,
        "begin: " + zipFile2,
        "done: " + zipFile2
      )
    }

  }
}

class AtmosFileUnzipperSingleBatchSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential

  implicit val materializer = ActorMaterializer()


  val outerSystem = system
  testActor ! SetAutoPilot(new AutoPilot {
    def run(sender: ActorRef, msg: Any): AutoPilot = {
      SimpleTestRouter.defaultReceive(sender)(msg)
      keepRunning
    }
  })


  "Running a single batch " >> {
    val listOfZips = List("drt_dq_170411_104441_4850.zip")
    val sourceOfZips = Source(listOfZips)

    "fetches the list of zips unzips them and tells the Actor" >> {


      val calledBatchComplete: scala.collection.mutable.MutableList[String] = mutable.MutableList[String]()

      val batchFileState = SignallingBatchFileState("", listOfZips)

      AtmosManifestFilePolling.runSingleBatch(DateTime.now(), sourceOfZips, (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
        """
          |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
        """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil), testActor, batchFileState, 90 seconds)

      expectMsg(ManifestZipFileInit)
      expectMsgAnyClassOf(classOf[VoyageManifest])

      success
    }
    "updates the latest file" >> {
      val batchFileState = SignallingBatchFileState("", listOfZips)

      AtmosManifestFilePolling.runSingleBatch(DateTime.now(), sourceOfZips, (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
        """
          |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
        """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil), testActor, batchFileState, 90 seconds)

      val expected = "drt_dq_170411_104441_4850.zip"
      Await.ready(batchFileState.onZipFileProcessCalled(expected), 1 second)
      batchFileState.latestFile === expected
    }

    "given the latest filename is the file in the listOfZips then latest file remains latest file AND no messages are sent to the actor" >> {
      val batchFileState = SignallingBatchFileState("drt_dq_170411_104441_4850.zip", listOfZips)

      AtmosManifestFilePolling.runSingleBatch(DateTime.now(), sourceOfZips, (fn) => Future(Nil), testActor, batchFileState, 90 seconds)

      val expected = "drt_dq_170411_104441_4850.zip"
      expectNoMsg()
      batchFileState.latestFile === expected
    }

    "given the latest filename is the earliest file in the listOfZips then latest file is updated, the unzipped content is request and messages are sent to the actor" >> {
      val listOfZips = List("drt_dq_170410_070001_1234.zip", "drt_dq_170411_104441_4850.zip")
      val sourceOfZips = Source(listOfZips)
      val batchFileState = SignallingBatchFileState("drt_dq_170410_070001_1234.zip", listOfZips)


      val requestsForUnzippedContent = mutable.MutableList[String]()

      AtmosManifestFilePolling.runSingleBatch(DateTime.now(), sourceOfZips, (fn) => {
        requestsForUnzippedContent += fn
        Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil)
      }, testActor, batchFileState, 4 seconds)

      expectMsg(ManifestZipFileInit)
      expectMsgAnyClassOf(classOf[VoyageManifest])
      expectMsgAnyClassOf(classOf[VoyageManifestZipFileComplete])

      val expectedLatestFile = "drt_dq_170411_104441_4850.zip"
      batchFileState.latestFile === expectedLatestFile && requestsForUnzippedContent === mutable.MutableList("drt_dq_170411_104441_4850.zip")
    }

    "sort order of files returned doesn't matter" >> {
      val listOfZips = List("drt_dq_170411_104441_4850.zip", "drt_dq_170410_070001_1234.zip")
      val sourceOfZips = Source(listOfZips)

      val batchFileState = SignallingBatchFileState("drt_dq_170410_070001_1234.zip", listOfZips)

      val requestsForUnzippedContent = mutable.MutableList[String]()

      AtmosManifestFilePolling.runSingleBatch(DateTime.now(), sourceOfZips, (fn) => {
        requestsForUnzippedContent += fn
        Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil)
      }, testActor, batchFileState, 4 seconds)

      expectMsg(ManifestZipFileInit)
      expectMsgAnyClassOf(classOf[VoyageManifest])
      expectMsgAnyClassOf(classOf[VoyageManifestZipFileComplete])

      val expectedLatestFile = "drt_dq_170411_104441_4850.zip"
      batchFileState.latestFile === expectedLatestFile && requestsForUnzippedContent === mutable.MutableList("drt_dq_170411_104441_4850.zip")

    }
  }
}