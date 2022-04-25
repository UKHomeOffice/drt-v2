package drt.server.feeds.common

import org.apache.poi.ss.usermodel.{Cell, Row, Sheet, Workbook}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.util.Date
import scala.util.Try
import scala.util.matching.Regex

object XlsExtractorUtil {

  val workbook: String => Workbook = xlsFilePath => new XSSFWorkbook(xlsFilePath)

  val sheetMapByName: (String, Workbook) => Sheet = (sheetName, workbook) => workbook.getSheet(sheetName)

  val sheetMapByIndex: (Int, Workbook) => Sheet = (index, workbook) => workbook.getSheetAt(index)

  val getNumberOfSheets: Workbook => Int = workbook => workbook.getNumberOfSheets()

  val stringCellOption: (Int, Row) => Option[String] = (index, row) => Option(row.getCell(index).getStringCellValue)

  val numericCellOption: (Int, Row) => Option[Double] = (index, row) => Option(row.getCell(index).getNumericCellValue)

  val dateCell: (Int, Row) => Date = (index, row) => row.getCell(index).getDateCellValue

  val stringCell: (Int, Row) => String = (index, row) => row.getCell(index).getStringCellValue

  val headingIndexByName: Row => Map[String, Int] = headingRow => (headingRow.getFirstCellNum to headingRow.getLastCellNum collect {
    case index if headingRow.getCell(index) != null && headingRow.getCell(1).getCellType != Cell.CELL_TYPE_BLANK => stringCell(index, headingRow) -> index
  }).toMap

  val tryNumericThenStringCellDoubleOption: (Int, Row) => Double = (index, row) => Try(numericCellOption(index, row).getOrElse(0.0)).getOrElse(stringCellOption(index, row).map(_.toDouble).getOrElse(0.0))

  val flightNumberRegex: Regex = "^([0-9]{1,4})([A-Z]*)$".r

  val tryNumericThenStringCellIntOption: (Int, Row) => Int = (index, row) => Try(
    stringCellOption(index, row)
      .map {
        case flightNumberRegex(number, _) => number.toInt
        case invalidData => throw new Exception(s"Failed to extract numeric flight number from '$invalidData'")
      }
      .getOrElse(throw new Exception(s"Failed to extract numeric flight number. No string found on row $row at cell $index")))
      .getOrElse(throw new Exception(s"Failed to extract numeric flight number. No string found on row $row at cell $index"))
}
