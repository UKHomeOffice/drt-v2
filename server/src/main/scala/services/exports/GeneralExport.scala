package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}

object GeneralExport {
  def toCsv[A](start: LocalDate,
               end: LocalDate,
               dataStream: (LocalDate, LocalDate) => Source[(LocalDate, A), NotUsed],
               dataToCsvRows: (LocalDate, A) => Future[Seq[String]]
              )
              (implicit ec: ExecutionContext): Source[String, NotUsed] =
    dataStream(start, end)
      .mapAsync(1) {
        case (localDate, data) => dataToCsvRows(localDate, data).map(_.mkString)
      }
      .collect {
        case line if line.nonEmpty => line
      }

  def toDailyRows[A, T](start: LocalDate,
                        end: LocalDate,
                        dataStream: (LocalDate, LocalDate) => Source[(LocalDate, A), NotUsed],
                        transform: (LocalDate, A) => Future[Seq[(LocalDate, Int, T)]]
                       )
                       (implicit ec: ExecutionContext, toRow: Seq[(LocalDate, Int, T)] => String): Source[String, NotUsed] =
    dataStream(start, end)
      .mapAsync(1) {
        case (localDate, data) => transform(localDate, data).map(toRow(_))
      }
      .collect {
        case line if line.nonEmpty => line
      }

  def toTotalsRow[A, T](start: LocalDate,
                        end: LocalDate,
                        dataStream: (LocalDate, LocalDate) => Source[(LocalDate, A), NotUsed],
                        fold: ((LocalDate, A), (LocalDate, A)) => Future[T]
                       )
                       (implicit ec: ExecutionContext, toRow: Seq[T] => String): Source[String, NotUsed] =
    dataStream(start, end)
      .map(a => a._2)
}
