package drt.server.feeds.common

import java.util.Date

import org.apache.poi.ss.usermodel.{Cell, Row, Sheet, Workbook}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

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

}
