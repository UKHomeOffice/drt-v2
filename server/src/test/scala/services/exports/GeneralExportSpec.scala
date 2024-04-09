package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class GeneralExportSpec extends CrunchTestLike {
  "toCsv should give a row for each element returned in the data stream" >> {
    val start = LocalDate(2020, 1, 1)
    val end = LocalDate(2020, 1, 2)

    val dataStream: (LocalDate, LocalDate) => Source[(LocalDate, Int), NotUsed] =
      (s, e) => Source(List(
        (s, 1),
        (e, 2),
      ))

    val toRows: (LocalDate, Int) => Future[Seq[String]] = {
      (date, int) =>
        Future.successful(Seq(s"${date.toString()},${int.toString}\n"))
    }

    "Given a data stream provider & a data to rows function" >> {
      val csvStream = GeneralExport.toCsv(start, end, dataStream, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second).toList
      val expected = List(
        "2020-01-01,1\n",
        "2020-01-02,2\n",
      )

      result === expected
    }
  }
}
