package services.exports

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
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
}
