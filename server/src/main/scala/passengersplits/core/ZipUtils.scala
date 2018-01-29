package passengersplits.core

import java.nio.charset.StandardCharsets._
import java.util.zip.{ZipEntry, ZipInputStream}

import scala.collection.mutable.ArrayBuffer

object ZipUtils {

  case class UnzippedFileContent(filename: String, content: String, zipFilename: Option[String] = None)

  def unzipAllFilesInStream(unzippedStream: ZipInputStream): Stream[UnzippedFileContent] = {
    unzipAllFilesInStream(unzippedStream, Option(unzippedStream.getNextEntry))
  }

  def unzipAllFilesInStream(unzippedStream: ZipInputStream, ze: Option[ZipEntry]): Stream[UnzippedFileContent] = {
    ze match {
      case None => Stream.empty
      case Some(zipEntry) =>
        val name: String = zipEntry.getName
        val entry: String = ZipUtils.getZipEntry(unzippedStream)
        val maybeEntry1: Option[ZipEntry] = Option(unzippedStream.getNextEntry)
        UnzippedFileContent(name, entry) #::
          unzipAllFilesInStream(unzippedStream, maybeEntry1)
    }
  }

  def getZipEntry(zis: ZipInputStream): String = {
    val buffer = new Array[Byte](4096)
    val stringBuffer = new ArrayBuffer[Byte]()
    var len: Int = zis.read(buffer)

    while (len > 0) {
      stringBuffer ++= buffer.take(len)
      len = zis.read(buffer)
    }

    new String(stringBuffer.toArray, UTF_8)
  }
}
