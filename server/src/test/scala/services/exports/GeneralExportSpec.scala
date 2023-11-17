package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class GeneralExportSpec extends CrunchTestLike {
  "toCsv should give a row for each element returned in the data stream" >> {
    val start = LocalDate(2020, 1, 1)
    val end = LocalDate(2020, 1, 2)
    val terminal = Terminal("T1")

    val dataStream: (LocalDate, LocalDate, Terminal) => Source[(LocalDate, (Terminal, Int)), NotUsed] =
      (s, e, t) => Source(List(
        (s, (t, 1)),
        (e, (t, 2)),
      ))

    val toRows: (LocalDate, (Terminal, Int)) => Future[Seq[String]] = {
      (date, terminalAndInt) =>
        Future.successful(Seq(s"${date.toString()},${terminalAndInt._1.toString},${terminalAndInt._2}\n"))
    }

    "Given a data stream provider & a data to rows function" >> {
      val csvStream = GeneralExport.toCsv(start, end, terminal, dataStream, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second).toList
      val expected = List(
        "2020-01-01,T1,1\n",
        "2020-01-02,T1,2\n",
      )

      result === expected
    }
  }
}
