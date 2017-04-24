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
import passengersplits.polling.AtmosFilePolling.BatchFileState
import passengersplits.s3.PromiseSignals
import services.SDate

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class AtmosFileUnzipperSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  implicit val materializer = ActorMaterializer()


  val outerSystem = system

  "Running a single batch " >> {
    "fetches the list of zips unzips them and tells the Actor" >> {

      val listOfZips = Source(List("drt_dq_170411_104441_4850.zip"))

      val calledBatchComplete: scala.collection.mutable.MutableList[String] = mutable.MutableList[String]()

      val batchFileState = new BatchFileState {
        private val promiseBatchDone = PromiseSignals.promisedDone
        val callbackWasCalled: Future[Done] = promiseBatchDone.future

        def onBatchComplete(filename: String) = {
          calledBatchComplete += filename
          promiseBatchDone.complete(Try(Done))
        }

        def latestFile = ""

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

      val calledBatchComplete: scala.collection.mutable.MutableList[String] = mutable.MutableList[String]()

      val batchFileState = new BatchFileState {
        private val promiseBatchDone = PromiseSignals.promisedDone
        val callbackWasCalled: Future[Done] = promiseBatchDone.future

        def onBatchComplete(filename: String) = {
          calledBatchComplete += filename
          promiseBatchDone.complete(Try(Done))
        }

        def latestFile = ""

      }

      AtmosFilePolling.runSingleBatch(DateTime.now(), listOfZips,
        (fn) => Future(UnzippedFileContent("drt_160302_060000_FR3631_DC_4089.json",
          """
            |{"EventCode": "CI", "DeparturePortCode": "SVG", "VoyageNumberTrailingLetter": "", "ArrivalPortCode": "ABZ", "DeparturePortCountryCode": "NOR", "VoyageNumber": "3631", "VoyageKey": "a1c9cbec34df3f33ca5e1e934f920364", "ScheduledDateOfDeparture": "2016-03-03", "ScheduledDateOfArrival": "2016-03-03", "CarrierType": "AIR", "CarrierCode": "FR", "ScheduledTimeOfDeparture": "08:05:00", "PassengerList": [{"DocumentIssuingCountryCode": "BWA", "PersonType": "P", "DocumentLevel": "Primary", "Age": "61", "DisembarkationPortCode": "ABZ", "InTransitFlag": "N", "DisembarkationPortCountryCode": "GBR", "NationalityCountryEEAFlag": "", "DocumentType": "P", "PoavKey": "1768895616a4fd245b3a2c31f8fbd407", "NationalityCountryCode": "BWA"}], "ScheduledTimeOfArrival": "09:10:00", "FileId": "drt_160302_060000_FR3631_DC_4089"}
          """.stripMargin, Option("drt_dq_170411_104441_4850.zip")) :: Nil),
        testActor, batchFileState
      )

      val expected = mutable.MutableList("drt_dq_170411_104441_4850.zip")
      Await.ready(batchFileState.callbackWasCalled, 1 second)
      calledBatchComplete === expected
    }
  }
}
