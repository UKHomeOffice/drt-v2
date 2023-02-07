package drt.server.feeds.common

import org.specs2.mutable.Specification

class XlsExtractorUtilSpec extends Specification {
  "Given a sheet" >> {
    "I should be able to open it" >> {
      val filePath = getClass.getClassLoader.getResource("StnForecast-2023-02-06.xlsx").getPath
      val workbook = XlsExtractorUtil.workbook(filePath)
      val sheet = XlsExtractorUtil.sheetMapByName("Arrivals by flight", workbook)
      sheet.getSheetName === "Arrivals by flight"
    }
  }
}
