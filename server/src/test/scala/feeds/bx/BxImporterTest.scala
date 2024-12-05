package feeds.bx


import org.apache.poi.ss.usermodel.{DataFormatter, WorkbookFactory}
import org.joda.time.DateTimeZone
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonId
import uk.gov.homeoffice.drt.time.DateRange

import java.io.File
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Try


class BxImporterTest extends AnyWordSpec with Matchers {

  "BxImporter" should {
    "import the BX data" in {
      val monthYearRegex = """.+Gate Type and Hour between 01 ([a-zA-Z]+) ([0-9]{4}).+""".r
      val f = new File("/home/rich/Downloads/PRAU BF Data Cell - DrT Monthly Report - Oct24.xlsx")
      val workbook = WorkbookFactory.create(f)

      val sheet = workbook.iterator().asScala.find(_.getSheetName == "Data Response").getOrElse(throw new Exception("Sheet not found"))

      val formatter = new DataFormatter()

      val fromMonthRow = sheet.iterator().asScala.dropWhile { row =>
        println(s"checking row ${row.getRowNum}")
        !row.cellIterator().asScala.exists { cell =>
          formatter.formatCellValue(cell) match {
            case monthYearRegex(month, year) =>
//              println(s"Month: $month, Year: $year")
              true
            case _ =>
              false
          }
        }
      }
      val (month, year) = fromMonthRow.next().cellIterator().asScala.toSeq.headOption match {
        case Some(cell) =>
          formatter.formatCellValue(cell) match {
            case monthYearRegex(month, year) =>
              (month, year)
            case _ =>
              throw new Exception("Month and year not found")
          }
        case None =>
          throw new Exception("Month and year not found")
      }
      println(s"Month: $month, Year: $year")

      val fromHeadingsRow = fromMonthRow.dropWhile { row =>
        println(s"checking row ${row.getRowNum}")
        val cells = row.cellIterator().asScala.toIndexedSeq

        if (cells.size < 2) {
          false
        } else {
          val cellMatch1 = formatter.formatCellValue(cells(0)) == "Port"
          val cellMatch2 = formatter.formatCellValue(cells(1)) == "Terminal"
          !(cellMatch1 && cellMatch2)
        }
      }

      println(s"Headings: ${fromHeadingsRow.next().cellIterator().asScala.map(formatter.formatCellValue).mkString(", ")}")

      val startDate = SDate(f"$year-${SDate.monthsOfTheYear.indexOf(month) + 1}%02d-01")
      val endDate = startDate.addMonths(1).addDays(-1)
      val dateRange = DateRange(startDate.toUtcDate, endDate.toUtcDate)

      val dataRows = fromHeadingsRow.drop(1)
      val cellOffset = 2

      val hourData = dataRows.toSeq.map { row =>
        val port = formatter.formatCellValue(row.getCell(cellOffset + 0))
        val terminal = formatter.formatCellValue(row.getCell(cellOffset + 1))
        val gateType = formatter.formatCellValue(row.getCell(cellOffset + 2))
        val hour = formatter.formatCellValue(row.getCell(cellOffset + 3)).toInt

        val pax = dateRange.zipWithIndex.map { case (date, idx) =>
          Try(formatter.formatCellValue(row.getCell(cellOffset + 4 + idx)).toDouble.toInt).toOption.map { count =>
            (date, count)
          }
        }

        val data = (port, terminal, gateType, hour, pax.toList)
        println(s"Row: $data")
        data
      }
    }
  }
}
