package services.`export`

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}

import scala.concurrent.Await
import scala.concurrent.duration._

object CsvTestHelper {
  def takeCSVLines(csvResult: String, linesToTake: Int): String = {
    csvResult
      .split("\n")
      .take(linesToTake)
      .mkString("\n")
  }

  def dropHeadings(csvResult: String): String = csvResult.split("\n").drop(2).mkString("\n")

  def resultStreamToCSV(resultSource: Source[String, NotUsed])(implicit mat: Materializer): String = {
    Await.result(resultSource.runWith(Sink.seq), 1.second).mkString
  }

}
