package s3

import java.io.InputStream
import java.util.zip.ZipInputStream

import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import passengersplits.core.ZipUtils
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.parsing.PassengerInfoParser.{EventCodes, PassengerInfoJson, VoyagePassengerInfo}
import java.io.InputStream
import java.util.zip.ZipInputStream

import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import passengersplits.parsing.PassengerInfoParser

class ZipSpec extends Specification with Matchers {

  import PassengerInfoParser._
  import FlightPassengerInfoProtocol._
  import spray.json._
  "Can extract file content from a zip" >> {
    "given a zip file inputstream" in {
      val results = ZipUtils.usingZip(new ZipInputStream(openResourceZip)) {
        (zip: ZipInputStream) =>
          val unzippedStream: Stream[UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(zip)
          unzippedStream.toList
      }
      val numberOfFIlesInZip: Int = results.toList.length
      numberOfFIlesInZip should beEqualTo(59)
    }
    "can parse from the zipped file" in {
      val results = ZipUtils.usingZip(new ZipInputStream(openResourceZip)) {
        zip =>
          val unzippedStream: Stream[UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(zip)
          unzippedStream.take(1).map {
            fc => (fc.filename, fc.content.parseJson.convertTo[VoyagePassengerInfo])
          }
      }
      results.toList match {
        case ("drt_160302_165000_SU2584_CI_0915.json",
        VoyagePassengerInfo(EventCodes.CheckIn, "LHR", departurePort, "2584", "SU", "2016-03-02", "21:05:00", _)) :: Nil => true
        case default =>
          assert(false, "Didn't match expectation, got: " + default)
          false
      }
    }
    "a PassengerInfo has origin country, and DocumentType" in {
      val results = ZipUtils.usingZip(new ZipInputStream(openResourceZip)) {
        zip =>
          val unzippedStream: Stream[UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(zip)
          unzippedStream.take(1).map {
            fc => (fc.filename, fc.content.parseJson.convertTo[VoyagePassengerInfo])
          }
      }
      results.toList match {
        case ("drt_160302_165000_SU2584_CI_0915.json",
        VoyagePassengerInfo(EventCodes.CheckIn, "LHR", departurePort,  "2584", "SU", "2016-03-02", "21:05:00",
        PassengerInfoJson(Some("V"), "GTM", "", Some("67")) :: passengerInfoTail)) :: Nil => true
        case default =>
          assert(false, "Didn't match expectation, got: " + default)
          false
      }
    }
  }

  def openResourceZip: InputStream = {
    getClass.getClassLoader.getResourceAsStream("s3content/zippedtest/drt_dq_160617_165737_5153.zip")
  }
}
