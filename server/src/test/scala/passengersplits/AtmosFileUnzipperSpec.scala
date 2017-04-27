package passengersplits

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import passengersplits.polling.AtmosFilePolling
import passengersplits.polling.AtmosFilePolling.BatchFileStateImpl
import services.mocklogger.MockLoggingLike
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import org.mockito.{ArgumentCaptor, ArgumentMatcher, Mockito}
import org.mockito.Matchers.argThat
import org.mockito.Mockito.{mock, verify, when}
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try


class WhenUnzippingOrderMattersSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {
  implicit val materializer = ActorMaterializer()

  "Given one zip file " >> {
    "the latest filename should not be updated until the actor has received (acked) all the VoyagePaxInfo messages from the zipfile" >> {
      println("order of messages")
      val twoTicks = Source(List(new DateTime(2017, 1, 1, 12, 33, 20)))

      def shouldOnlyBeCalledOnce_ListOfFilenames() = Source(List("drt_160302_060000_FR3631_DC_4089.json"))

      var callsToUnzippedContent = 0

      def unzippedContentFunc(fn: String) = {
        callsToUnzippedContent += 1

        Future {
          Thread.sleep(1000) /// take longer than we need, but not too long
          UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
            """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil
        }
      }

      val batchFileState = new SignallingBatchFileState {
        var latestFileName: String = ""

        override def onZipFileProcessed(filename: String): Unit = {
          system.log.info(s"onZipFileProcess ${filename}")
          assert(receiveOne(0 seconds) match {
            case vpi: VoyagePassengerInfo => true
            case un => system.log.error(s"unexpected $un")
              false
          })
          system.log.info(s"onZipFileProcess ${filename} got msg")


          super.onZipFileProcessed(filename)
        }
      }

      val pollingFuture = AtmosFilePolling.beginPollingImpl(testActor,
        twoTicks,
        shouldOnlyBeCalledOnce_ListOfFilenames,
        unzippedContentFunc,
        batchFileState
      )

      val expectedZipFile = "drt_160302_060000_FR3631_DC_4089.json"
      val expectedNumberOfCalls = 1

      pollingFuture.onFailure {
        case f: Throwable => {
          system.log.error(f, "failure on outer batches")
          failure
        }
      }
      val futureOfAllBatches = Future.sequence(List(batchFileState.callbackWasCalled, pollingFuture))

      (Await.result(futureOfAllBatches, 4 seconds), callsToUnzippedContent) === (List(expectedZipFile, Done), expectedNumberOfCalls)
    }
  }

}

class WhenUnzippingIfJsonIsBadSpec extends
  TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty()))
  with SpecificationLike with MockLoggingLike {
  implicit val materializer = ActorMaterializer()

  "Given a zip file with a bad json " >> {
    "we should log the error, and process the next file" >> {
      system.log.info("unzipping is bad")
      val batchTick = Source(List(new DateTime(2017, 1, 1, 12, 33, 20)))

      def shouldOnlyBeCalledOnce_ListOfFilenames() = Source(List(
        "drt_dq_170411_104441_4850.zip"
      ))

      var callsToUnzippedContent = 0

      def unzippedContentFunc(fn: String) = {
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

      val batchFileState = new SignallingBatchFileState {
        var latestFileName: String = ""

        override def onZipFileProcessed(filename: String): Unit = {
          system.log.info(s"onZipFileProcess ${filename}")
          assert(receiveOne(0 seconds) match {
            case vpi: VoyagePassengerInfo => true
            case un => system.log.error(s"unexpected $un")
              false
          })
          system.log.info(s"onZipFileProcess ${filename} got msg")

          super.onZipFileProcessed(filename)
        }
      }

      withMockAppender { mockAppender =>
        val pollingFuture = AtmosFilePolling.beginPollingImpl(testActor,
          batchTick,
          shouldOnlyBeCalledOnce_ListOfFilenames,
          unzippedContentFunc,
          batchFileState
        )

        val expectedZipFile = "drt_dq_170411_104441_4850.zip"
        val exp = 1

        pollingFuture.onFailure {
          case f: Throwable => {
            system.log.error(f, "failure on outer batches")
            failure
          }
        }
        val futureOfAllBatches = Future.sequence(List(batchFileState.callbackWasCalled, pollingFuture))

        (Await.result(futureOfAllBatches, 4 seconds), callsToUnzippedContent) === (List(expectedZipFile, Done), exp)
        val argumentCaptor = ArgumentCaptor.forClass(classOf[ILoggingEvent])
        verify(mockAppender, Mockito.atLeastOnce()).doAppend(argumentCaptor.capture())

        val loggingCalls: List[ILoggingEvent] = argumentCaptor.getAllValues().asScala.toList

        val expectedMessage = "Failed to parse voyage passenger info: 'drt_150302_060000_BA1234_DC_7890.json'"
        loggingCalls.exists(le => le.getLevel() == Level.WARN && le.getFormattedMessage().contains(expectedMessage))
      }
    }
  }

}

trait SignallingBatchFileState extends BatchFileStateImpl {
  private val promiseBatchDone = Promise[String]()
  val callbackWasCalled: Future[String] = promiseBatchDone.future

  override def onZipFileProcessed(filename: String): Unit = {
    super.onZipFileProcessed(filename)
    promiseBatchDone.complete(Try(filename))
  }
}

class AtmosFileUnzipperSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  isolated
  sequential

  implicit val materializer = ActorMaterializer()


  val outerSystem = system

  "Running multiple batches " >> {
    "If a batch is already running, when we tick again that tick is ignored, unzippedCalls count should be 1" >> {
      println("suppress second batch test")
      val twoTicks = Source(List(DateTime.now(), DateTime.now().plusMinutes(1)))

      def shouldOnlyBeCalledOnce_ListOfFilenames() = Source(List("drt_160302_060000_FR3631_DC_4089.json"))

      var callsToUnzippedContent = 0

      def unzippedContentFunc(fn: String) = {
        callsToUnzippedContent += 1

        Future {
          Thread.sleep(1000) /// take longer than we need, but not too long
          UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
            """
{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil
        }
      }

      val batchFileState = new SignallingBatchFileState {
        var latestFileName: String = ""
      }

      AtmosFilePolling.beginPollingImpl(testActor,
        twoTicks,
        shouldOnlyBeCalledOnce_ListOfFilenames,
        unzippedContentFunc,
        batchFileState
      )

      val expectedZipFile = "drt_160302_060000_FR3631_DC_4089.json"
      val expectedNumberOfCalls = 1

      (Await.result(batchFileState.callbackWasCalled, 2 second), callsToUnzippedContent) === (expectedZipFile, expectedNumberOfCalls)
    }
  }


  "Running a single batch " >> {
    "fetches the list of zips unzips them and tells the Actor" >> {

      val listOfZips = Source(List("drt_dq_170411_104441_4850.zip"))

      val calledBatchComplete: scala.collection.mutable.MutableList[String] = mutable.MutableList[String]()

      val batchFileState = new SignallingBatchFileState {
        var latestFileName: String = ""
      }

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil),
        testActor, batchFileState
      )


      expectMsgAnyClassOf(classOf[VoyagePassengerInfo])

      success
    }
    "updates the latest file" >> {

      val listOfZips = Source(List("drt_dq_170411_104441_4850.zip"))


      val batchFileState = new SignallingBatchFileState {
        var latestFileName: String = ""
      }

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil),
        testActor, batchFileState
      )

      val expected = "drt_dq_170411_104441_4850.zip"
      Await.ready(batchFileState.callbackWasCalled, 1 second)
      batchFileState.latestFile === expected
    }

    "given the latest filename is the file in the listOfZips then latest file remains latest file AND no messages are sent to the actor" >> {

      val listOfZips = Source(List("drt_dq_170411_104441_4850.zip"))

      val batchFileState = new SignallingBatchFileState with BatchFileStateImpl {
        var latestFileName: String = "drt_dq_170411_104441_4850.zip"
      }

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => Future(Nil),
        testActor, batchFileState
      )

      val expected = "drt_dq_170411_104441_4850.zip"
      expectNoMsg()
      batchFileState.latestFile === expected
    }

    "given the latest filename is the earliest file in the listOfZips then latest file is updated, the unzipped content is request and messages are sent to the actor" >> {
      val listOfZips = Source(List("drt_dq_170410_070001_1234.zip", "drt_dq_170411_104441_4850.zip"))

      val batchFileState = new SignallingBatchFileState with BatchFileStateImpl {
        var latestFileName: String = "drt_dq_170410_070001_1234.zip"
      }

      val requestsForUnzippedContent = mutable.MutableList[String]()

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => {
          requestsForUnzippedContent += fn
          Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
            """
              |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
            """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil)
        },
        testActor, batchFileState
      )

      expectMsgAnyClassOf(classOf[VoyagePassengerInfo])

      val expectedLatestFile = "drt_dq_170411_104441_4850.zip"
      batchFileState.latestFile === expectedLatestFile && requestsForUnzippedContent === mutable.MutableList("drt_dq_170411_104441_4850.zip")
    }

    "sort order of files returned doesn't matter" >> {
      val listOfZips = Source(List("drt_dq_170411_104441_4850.zip", "drt_dq_170410_070001_1234.zip"))

      val batchFileState = new SignallingBatchFileState with BatchFileStateImpl {
        var latestFileName: String = "drt_dq_170410_070001_1234.zip"
      }

      val requestsForUnzippedContent = mutable.MutableList[String]()

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => {
          requestsForUnzippedContent += fn
          Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
            """
              |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
            """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil)
        },
        testActor, batchFileState
      )

      expectMsgAnyClassOf(classOf[VoyagePassengerInfo])

      val expectedLatestFile = "drt_dq_170411_104441_4850.zip"
      batchFileState.latestFile === expectedLatestFile && requestsForUnzippedContent === mutable.MutableList("drt_dq_170411_104441_4850.zip")

    }
  }
}