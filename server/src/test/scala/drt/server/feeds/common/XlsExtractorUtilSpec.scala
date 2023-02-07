package drt.server.feeds.common

import org.specs2.mutable.Specification

class XlsExtractorUtilSpec extends Specification {
  "Given a sheet" >> {
    "I should be able to open it" >> {
      val filePath = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath
      val workbook = XlsExtractorUtil.workbook(filePath)
      val sheet = XlsExtractorUtil.sheetMapByName("Arrivals by flight", workbook)
      sheet.getSheetName === "Arrivals by flight"
    }
  }
}
