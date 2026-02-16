package drt.server.feeds.lgw

import net.schmizz.sshj.sftp.{FileAttributes, PathComponents, RemoteResourceInfo, SFTPClient}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters._

class LgwForecastSftpServiceSpec extends Specification {
  def makeRemoteResourceInfo(name: String): RemoteResourceInfo = {
    val mockPathComponents = mock(classOf[PathComponents])
    val mockFileAttributes = mock(classOf[FileAttributes])
    new RemoteResourceInfo(mockPathComponents, mockFileAttributes) {
      override def getName: String = name
      override def getPath: String = name
    }
  }

  def withFiles(files: List[String])(test: SFTPClient => org.specs2.matcher.MatchResult[Option[String]]): MatchResult[Option[String]] = {
    val sftp: SFTPClient = mock(classOf[SFTPClient])
    when(sftp.ls(ArgumentMatchers.any[String])).thenReturn(files.map(makeRemoteResourceInfo).asJava)
    test(sftp)
  }

  val service: LgwForecastSftpService = LgwForecastSftpService("host", 22, "user", "pass", "/prefix")

  "latestFileName" should {
    "prefer the new format if both new and legacy files exist" in {
      withFiles(List("LGWARRFORECAST-2024-06-01.csv", "2024-05-31-LGWArrForecast.csv")) { sftp =>
        service.latestFileName(sftp) must beSome("LGWARRFORECAST-2024-06-01.csv")
      }
    }

    "prefer the new format if both new and legacy files exist with the same date" in {
      withFiles(List("2024-06-01-LGWArrForecast.csv", "LGWARRFORECAST-2024-06-01.csv")) { sftp =>
        service.latestFileName(sftp) must beSome("LGWARRFORECAST-2024-06-01.csv")
      }
    }

    "return the newest file with new format" in {
      withFiles(List("LGWARRFORECAST-2024-06-01.csv", "LGWARRFORECAST-2024-06-02.csv", "LGWARRFORECAST-2024-06-03.csv")) { sftp =>
        service.latestFileName(sftp) must beSome("LGWARRFORECAST-2024-06-03.csv")
      }
    }

    "return the newest file with old format" in {
      withFiles(List("2024-06-01-LGWArrForecast.csv", "2024-06-02-LGWArrForecast.csv", "2024-06-03-LGWArrForecast.csv")) { sftp =>
        service.latestFileName(sftp) must beSome("2024-06-03-LGWArrForecast.csv")
      }
    }

    "fallback to legacy if no new format exists" in {
      withFiles(List("2024-05-31-LGWArrForecast.csv")) { sftp =>
        service.latestFileName(sftp) must beSome("2024-05-31-LGWArrForecast.csv")
      }
    }

    "return None if no matching new format files exist" in {
      //30th Feb is not a valid date, so this file should be ignored
      withFiles(List("LGWARRFORECAST-2024-02-30.csv")) { sftp =>
        service.latestFileName(sftp) must beNone
      }
    }

    "return None if no matching old format files exist" in {
      //incomplete date format, should be ignored
      withFiles(List("2024-02-LGWArrForecast.csv")) { sftp =>
        service.latestFileName(sftp) must beNone
      }
    }

    "return None if no matching format files exist, new or old" in {
      withFiles(List("2024-02-30-LGWArrForecast.csv", "LGWARRFORECAST-2024-02-30.csv")) { sftp =>
        service.latestFileName(sftp) must beNone
      }
    }

    "return None if no matching files exist" in {
      withFiles(List("random-file.csv")) { sftp =>
        service.latestFileName(sftp) must beNone
      }
    }
  }
}

