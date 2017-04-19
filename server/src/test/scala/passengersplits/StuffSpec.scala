package passengersplits

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import com.mfglabs.stream.SinkExt
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike
import passengersplits.s3.SimpleAtmosReader
import services.SDate

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class StuffSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  implicit val materializer = ActorMaterializer()

  val outerSystem = system

  "Given we are polling for a new batch of zip files" >> {

    "Then new files finder should find new files " >> {

      "when given part of a filename including the date portion" >> {
        val listOfFiles =
          List(
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


        Source(listOfFiles)

        var latest = ""

        def filter(s: Seq[String], x: String) = {
          s.filter(_ >= x)
        }

        import scala.concurrent.ExecutionContext.Implicits.global
        val source: Future[Done] = Source.tick(0 seconds, 1 seconds, NotUsed).take(5)
          .runForeach(_ => {
            val futureZipFiles = Source(listOfFiles).runWith(SinkExt.collect)
            futureZipFiles.foreach(x => {
              filter(x, latest).foreach(l => {
                println(s"l: $l, x: $x")
                latest = l
                println(s"latest: $latest")
                latest
              })
            })
          })

        Await.result(source, 10 second)

        println(s"latest at end: $latest")

        true
      }
    }
  }
}
