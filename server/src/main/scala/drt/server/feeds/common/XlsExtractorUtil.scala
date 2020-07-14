package drt.server.feeds.common

import java.util.Date

import org.apache.poi.ss.usermodel.{Row, Sheet, Workbook}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

object XlsExtractorUtil {

  val workbook: String => Workbook = xlsFilePath => new XSSFWorkbook(xlsFilePath)

  val sheetMapByName: (String, Workbook) => Sheet = (sheetName, workbook) => workbook.getSheet(sheetName)

  val sheetMapByIndex: (Int, Workbook) => Sheet = (index, workbook) => workbook.getSheetAt(index)

  val stringCell: (Int, Row) => Option[String] = (index, row) => Option(row.getCell(index).getStringCellValue)

  val numericCell: (Int, Row) => Option[Double] = (index, row) => Option(row.getCell(index).getNumericCellValue)

  val dateCell: (Int, Row) => Date = (index, row) => row.getCell(index).getDateCellValue
}
