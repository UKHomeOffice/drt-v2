package passengersplits

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.shared.MilliDate
import org.specs2.mutable.SpecificationLike
import services.SDate
import scala.collection.immutable.Seq

class AdvancePassengerInfoZipFilterSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", ConfigFactory.empty())) with SpecificationLike {

  implicit val materializer = ActorMaterializer()

  val outerSystem = system


  "Given we are polling for a new batch of zip files" >> {

    import passengersplits.polling.AtmosManifestFilePolling._

    "Then new files finder should find new files " >> {

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

        val after = filterToFilesNewerThan(listOfFiles, "drt_dq_170412")

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

        val after = filterToFilesNewerThan(listOfFiles, "drt_dq_170411_125440_4723.zip")

        val expected = Seq( "drt_dq_170411_131151_9959.zip", "drt_dq_170412_132903_1048.zip", "drt_dq_170412_134623_1858.zip")

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

        val after = filterToFilesNewerThan(listOfFiles, "drt_dq_170411_125440_4700.zip")

        val expected = Seq(
          "drt_dq_170411_125440_4723.zip",
          "drt_dq_170411_131151_9959.zip",
          "drt_dq_170412_132903_1048.zip",
          "drt_dq_170412_134623_1858.zip"
        )

        after === expected
      }

      "when given a full filename " >> {
        "and there are two files with the same date, but different random suffix" >> {
          "then we should process the random suffix that we  haven't just processed" >> {
            val listOfFiles = Seq(
              "drt_dq_170410_102318_9111.zip",
              "drt_dq_170410_102318_1031.zip"
            )

            filterToFilesNewerThan(listOfFiles, "drt_dq_170410_102318_9111.zip") === Seq("drt_dq_170410_102318_1031.zip")
          }
          "then we should process the random suffix that we  haven't just processed" >> {
            val listOfFiles = Seq(
              "drt_dq_170410_102318_9111.zip",
              "drt_dq_170410_102318_1031.zip"
            )

            filterToFilesNewerThan(listOfFiles, "drt_dq_170410_102318_1031.zip") === Seq("drt_dq_170410_102318_9111.zip")
          }
        }
        "and there is just one file with the suffix" >> {
          "then we should not reprocess that file" >> {
            val listOfFiles = Seq(
              "drt_dq_170410_102318_9111.zip"
            )

            filterToFilesNewerThan(listOfFiles, "drt_dq_170410_102318_9111.zip") === Seq()
          }
          "then we should process the random suffix that we  haven't just processed" >> {
            val listOfFiles = Seq(
              "drt_dq_170410_102318_9111.zip",
              "drt_dq_170410_102319_1031.zip"
            )

            filterToFilesNewerThan(listOfFiles, "drt_dq_170410_102318_9111.zip") === Seq("drt_dq_170410_102319_1031.zip")
          }
        }
      }
    }

    "Then we should start polling for files that arrived yesterday" >> {

      val date = SDate.parseString("2017-04-12T10:30:00.000Z")

      val expected = "drt_dq_170411"

      val result = previousDayDqFilename(date)

      result === expected
    }

    "Then we should start polling for files that arrived yesterday when yesterday is a single digit day" >> {

      val date = SDate.parseString("2017-04-10T10:30:00.000Z")

      val expected = "drt_dq_170409"

      val result = previousDayDqFilename(date)

      result === expected
    }
  }
}
