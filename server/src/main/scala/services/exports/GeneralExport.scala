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
                        transform: (LocalDate, A) => Future[(LocalDate, Int, T)],
                        toRow: (LocalDate, Int, T) => String,
                       )
                       (implicit ec: ExecutionContext): Source[String, NotUsed] =
    dataStream(start, end)
      .mapAsync(1) {
        case (localDate, data) => transform(localDate, data).map(toRow.tupled)
      }
      .collect {
        case line if line.nonEmpty => line
      }

  def toTotalsRow[A, B](start: LocalDate,
                        end: LocalDate,
                        dataStream: (LocalDate, LocalDate) => Source[(LocalDate, A), NotUsed],
                        transform: (LocalDate, A) => Future[B],
                        reduceSummaries: (B, B) => B,
                        toRow: B => String
                       ): Source[String, NotUsed] = {
    dataStream(start, end)
      .mapAsync(1) {
        case (localDate, data) => transform(localDate, data)
      }
      .reduce(reduceSummaries)
      .map(toRow)
  }
}
