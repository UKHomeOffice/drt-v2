package services

import actors.DateRange
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class LocalDateStreamSpec extends Specification {
  implicit val mat: Materializer = Materializer(ActorSystem("test"))

  "LocalDateStream" should {
    val startBufferDays = 0
    val endBufferDays = 0
    val transformData: (LocalDate, Seq[Int]) => String = (date, nos) => s"${date.toISOString} ${nos.mkString(",")}"

    "give an empty collection when the source provider is an empty stream" in {
      val utcStream: (UtcDate, UtcDate) => Source[(UtcDate, Seq[Int]), NotUsed] = (_, _) => Source.empty
      val localDateStream = LocalDateStream(utcStream, startBufferDays, endBufferDays, transformData)
      val result = Await.result(localDateStream(LocalDate(2020, 1, 1), LocalDate(2020, 1, 1)).runWith(Sink.seq), 1.second)
      result === Seq()
    }
    "give a single element collection when the source provider is a single element stream" in {
      val utcStream: (UtcDate, UtcDate) => Source[(UtcDate, Seq[Int]), NotUsed] = (_, _) => Source(List((UtcDate(2020, 1, 1), List(1, 2, 3))))
      val localDateStream = LocalDateStream(utcStream, startBufferDays, endBufferDays, transformData)
      val result = Await.result(localDateStream(LocalDate(2020, 1, 1), LocalDate(2020, 1, 1)).runWith(Sink.seq), 1.second)
      result === Seq((LocalDate(2020, 1, 1), "2020-01-01 1,2,3"))
    }
    "give a single element collection when the source provider is a two element stream" in {
      val utcStream: (UtcDate, UtcDate) => Source[(UtcDate, Seq[Int]), NotUsed] = (start, end) =>
        Source(DateRange(start, end).map(d => (d, List(1, 2, 3))))

      val localDateStream = LocalDateStream(utcStream, startBufferDays, endBufferDays, transformData)
      val result = Await.result(localDateStream(LocalDate(2020, 1, 1), LocalDate(2020, 1, 2)).runWith(Sink.seq), 1.second)
      result === Seq((LocalDate(2020, 1, 1), "2020-01-01 1,2,3,1,2,3"))
    }
  }

  "utcStartAndEnd with no buffer" should {
    "return the same dates in utc given local start and end dates outside of BST" in {
      val start = LocalDate(2023, 1, 1)
      val end = LocalDate(2023, 1, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(0, 0, start, end)
      utcStart === UtcDate(2023, 1, 1)
      utcEnd === UtcDate(2023, 1, 1)
    }
    "return the same dates in utc given local start outside of BST and end inside BST" in {
      val start = LocalDate(2023, 1, 1)
      val end = LocalDate(2023, 6, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(0, 0, start, end)
      utcStart === UtcDate(2023, 1, 1)
      utcEnd === UtcDate(2023, 6, 1)
    }
    "return a start date of the date before, and the same end date given local start inside of BST and end outside BST" in {
      val start = LocalDate(2023, 6, 1)
      val end = LocalDate(2024, 1, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(0, 0, start, end)
      utcStart === UtcDate(2023, 5, 31)
      utcEnd === UtcDate(2024, 1, 1)
    }
    "return a start date of the date before, and the same end date given local start and end dates inside BST" in {
      val start = LocalDate(2023, 6, 1)
      val end = LocalDate(2023, 6, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(0, 0, start, end)
      utcStart === UtcDate(2023, 5, 31)
      utcEnd === UtcDate(2023, 6, 1)
    }
  }
  "utcStartAndEnd with a one day start and end buffer" should {
    "return utc date with 1 day before and after given local start and end dates outside of BST" in {
      val start = LocalDate(2023, 1, 1)
      val end = LocalDate(2023, 1, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(1, 1, start, end)
      utcStart === UtcDate(2022, 12, 31)
      utcEnd === UtcDate(2023, 1, 2)
    }
    "return utc dates with 1 day before and after given local start outside of BST and end inside BST" in {
      val start = LocalDate(2023, 1, 1)
      val end = LocalDate(2023, 6, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(1, 1, start, end)
      utcStart === UtcDate(2022, 12, 31)
      utcEnd === UtcDate(2023, 6, 2)
    }
    "return a start date of 2 days before, and end one day after given local start inside of BST and end outside BST" in {
      val start = LocalDate(2023, 6, 1)
      val end = LocalDate(2024, 1, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(1, 1, start, end)
      utcStart === UtcDate(2023, 5, 30)
      utcEnd === UtcDate(2024, 1, 2)
    }
    "return a start date of 2 days before, and end one day after date given local start and end dates inside BST" in {
      val start = LocalDate(2023, 6, 1)
      val end = LocalDate(2023, 6, 1)
      val (utcStart, utcEnd) = LocalDateStream.utcStartAndEnd(1, 1, start, end)
      utcStart === UtcDate(2023, 5, 30)
      utcEnd === UtcDate(2023, 6, 2)
    }
  }
}
