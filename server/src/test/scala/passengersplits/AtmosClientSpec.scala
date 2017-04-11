package passengersplits

import java.util.Date

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import com.amazonaws.services.s3.model.Bucket
import com.mfglabs.stream.SinkExt
import com.typesafe.config.ConfigFactory
import drt.shared.MilliDate
import org.specs2.mutable.SpecificationLike
import org.specs2.mutable.Specification
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import passengersplits.core.{PassengerSplitsInfoByPortRouter, ZipUtils}
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import passengersplits.s3.{SimpleAtmosReader, VoyagePassengerInfoParser}
import akka.pattern._
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class AtmosClientSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {
  implicit val materializer = ActorMaterializer()
  val outerSystem = system
  val reader = new SimpleAtmosReader {

    override def skyscapeAtmosHost = "cas00003.skyscapecloud.com:8443"

    override def bucket = "drtdqprod"

    override def log = outerSystem.log

    override def zipFileNameFilter(filename: String) = ???

    override implicit def system = outerSystem
  }


  "The SimpleAtmosReader" should {


    //    "list all the files in the bucket as a stream" in {
    //      import akka.{NotUsed, Done}
    //
    //
    //      val builder = reader.createBuilder
    //
    //      val source: Source[(String, Date), NotUsed] = builder.listFilesAsStream(reader.bucket)
    //
    //      source.runForeach{
    //        case (filename, _) =>
    //          println(s"filename: $filename")
    //        case error => println(s"error: $error")
    //      }(materializer)
    //
    //
    //      Thread.sleep(1000000)
    //
    //      true
    //    }

    //    "list all the files in the bucket" in {
    //      import akka.{NotUsed, Done}
    //
    //
    //      val futurefiles: Future[IndexedSeq[(String, Date)]] = reader.createBuilder.listFilesAsStream(reader.bucket).runWith(SinkExt.collect)
    //
    //      val files: IndexedSeq[(String, Date)] = Await.result(futurefiles, 120000 seconds)
    //
    //      files.sortBy(_._1).map({
    //        case (filename, _) => println(filename)
    //        case error => println(s"error: $error")
    //      })
    //
    //      true
    //    }

    "download all a named zip file from the bucket" >> {
      implicit val timeout = Timeout(5 seconds)

      val fileFuture: Future[List[UnzippedFileContent]] = reader.zipFilenameToEventualFileContent("drt_dq_170411_112023_1877.zip")(materializer, scala.concurrent.ExecutionContext.global)

      val file: Seq[ZipUtils.UnzippedFileContent] = Await.result(fileFuture, 120000 seconds)
      val voyagePassengerInfos = file.map((flightManifest) => {
        VoyagePassengerInfoParser.parseVoyagePassengerInfo(flightManifest.content)
      })

      val passengerSplitsInfoByPortRouterActor = system.actorOf(Props[PassengerSplitsInfoByPortRouter], "passengersplits")

      voyagePassengerInfos.foreach {
        case Success(vpi) =>
          passengerSplitsInfoByPortRouterActor ! vpi
        case Failure(f) =>
          system.log.warning(s"Failed to parse split: $f")
      }
      import services.SDate.implicits._

      Thread.sleep(3000)
      val paxSplits = passengerSplitsInfoByPortRouterActor ? ReportVoyagePaxSplit("LGW", "BA", "2607", SDate.parseString("2017-04-11T12:05:00.000Z"))

      println(Await.result(paxSplits, 30 seconds))
      true
    }



    "New files finder should find new files " >> {
      import passengersplits.polling.AtmosFilePolling._
      "when given part of a filename including the date portion" >> {
        val listOfFiles = Seq(
          "drt_dq_170410_102318_9111.zip",
          "drt_dq_170411_103932_1507.zip",
          "drt_dq_170411_104441_4850.zip",
          "drt_dq_170411_112023_1877.zip",
          "drt_dq_170411_112629_9236.zip",
          "drt_dq_170411_115446_3007.zip",
          "drt_dq_170411_121640_4588.zip",
          "drt_dq_170411_123752_1652.zip",
          "drt_dq_170411_125440_4723.zip",
          "drt_dq_170411_131151_9959.zip",
          "drt_dq_170412_132903_1048.zip",
          "drt_dq_170412_134623_1858.zip"
        )

        val after = filesFromFile(listOfFiles, "drt_dq_170412")

        val expected = Seq("drt_dq_170412_132903_1048.zip", "drt_dq_170412_134623_1858.zip")

        after === expected
      }

      "when given a full filename" >> {
        val listOfFiles = Seq(
          "drt_dq_170410_102318_9111.zip",
          "drt_dq_170411_103932_1507.zip",
          "drt_dq_170411_104441_4850.zip",
          "drt_dq_170411_112023_1877.zip",
          "drt_dq_170411_112629_9236.zip",
          "drt_dq_170411_115446_3007.zip",
          "drt_dq_170411_121640_4588.zip",
          "drt_dq_170411_123752_1652.zip",
          "drt_dq_170411_125440_4723.zip",
          "drt_dq_170411_131151_9959.zip",
          "drt_dq_170412_132903_1048.zip",
          "drt_dq_170412_134623_1858.zip"
        )

        val after = filesFromFile(listOfFiles, "drt_dq_170411_125440_4723.zip")

        val expected = Seq("drt_dq_170411_125440_4723.zip", "drt_dq_170411_131151_9959.zip", "drt_dq_170412_132903_1048.zip", "drt_dq_170412_134623_1858.zip")

        after === expected
      }
      "when given a full filename" >> {
        val listOfFiles = Seq(
          "drt_dq_170410_102318_9111.zip",
          "drt_dq_170411_103932_1507.zip",
          "drt_dq_170411_104441_4850.zip",
          "drt_dq_170411_112023_1877.zip",
          "drt_dq_170411_112629_9236.zip",
          "drt_dq_170411_115446_3007.zip",
          "drt_dq_170411_121640_4588.zip",
          "drt_dq_170411_123752_1652.zip",
          "drt_dq_170411_125440_4723.zip",
          "drt_dq_170411_125440_4700.zip",
          "drt_dq_170411_131151_9959.zip",
          "drt_dq_170412_132903_1048.zip",
          "drt_dq_170412_134623_1858.zip"
        )

        val after = filesFromFile(listOfFiles, "drt_dq_170411_125440_4700.zip")

        val expected = Seq(
          "drt_dq_170411_125440_4723.zip",
          "drt_dq_170411_125440_4700.zip",
          "drt_dq_170411_131151_9959.zip",
          "drt_dq_170412_132903_1048.zip",
          "drt_dq_170412_134623_1858.zip"
        )

        after === expected
      }
    }


  }
}
