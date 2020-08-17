package drt.server.feeds.common

import java.util.Date

import org.apache.poi.ss.usermodel.{Cell, Row, Sheet, Workbook}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

object XlsExtractorUtil {

  val workbook: String => Workbook = xlsFilePath => new XSSFWorkbook(xlsFilePath)

  val sheetMapByName: (String, Workbook) => Sheet = (sheetName, workbook) => workbook.getSheet(sheetName)

  val sheetMapByIndex: (Int, Workbook) => Sheet = (index, workbook) => workbook.getSheetAt(index)

  val getNumberOfSheets: Workbook => Int = workbook => workbook.getNumberOfSheets()

  val stringCell: (Int, Row) => Option[String] = (index, row) => Option(row.getCell(index).getStringCellValue)

  val numericCell: (Int, Row) => Option[Double] = (index, row) => Option(row.getCell(index).getNumericCellValue)

  val dateCell: (Int, Row) => Date = (index, row) => row.getCell(index).getDateCellValue

  val headingIndexByName: Row => Map[Option[String], Int] = headingRow => (headingRow.getFirstCellNum to headingRow.getLastCellNum map { index =>
    if (headingRow.getCell(index) != null && headingRow.getCell(1).getCellType != Cell.CELL_TYPE_BLANK) {
      stringCell(index, headingRow) -> index
    } else None -> index
  }).toMap

}
