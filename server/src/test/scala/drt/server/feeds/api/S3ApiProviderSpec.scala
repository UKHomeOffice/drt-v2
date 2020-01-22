package drt.server.feeds.api

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import com.amazonaws.auth.AWSCredentials
import com.mfglabs.commons.aws.s3.AmazonS3Client
import com.typesafe.config.ConfigFactory
import drt.shared.SDateLike
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.Scope
import services.SDate

class S3ApiProviderSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  trait Context extends Scope {
    val s3ClientMock: AmazonS3Client = mock[AmazonS3Client]
    val awsCredentialsMock: AWSCredentials = mock[AWSCredentials]
    val s3ApiProvider: S3ApiProvider = new S3ApiProvider(awsCredentialsMock, "") {
      override def s3Client: AmazonS3Client = s3ClientMock
    }
  }

  "Can continue if there is an error getting a file from s3" in new Context {
    val list = List(ByteString(""), null)
    val iterator: Iterator[ByteString] = list.iterator
    val source: Source[ByteString, NotUsed] = Source.fromIterator(() => iterator)
    val result: (String, List[String]) = s3ApiProvider.fileNameAndContentFromZip("drt_dq_181108_000233_2957.zip", source)

    result must be_==(("drt_dq_181108_000233_2957.zip", List.empty))
  }

  "DQ latest file name for zips" >> {
    val nowString = "2020-01-15T12:10:15"
    val nowProvider: () => SDateLike = () => SDate(nowString)
    val oneHourMillis: Int = 60 * 60 * 1000
    val expiredFilename = "drt_dq_200115_110000"
    val unexpiredFilename = "drt_dq_200115_111015"
    val defaultLatestFilename = "drt_dq_200115_111015"

    s"Given a 'now' of $nowString and a 1 hour expiry time" >> {
      "When I ask for the default latest zip file name" >> {
        s"I should get $defaultLatestFilename" >> {
          val latest = S3ApiProvider.defaultApiLatestZipFilename(nowProvider, oneHourMillis)

          latest === defaultLatestFilename
        }
      }
    }

    "When I ask for a latest file name" >> {
      s"Given no existing latest file name, a 'now' of $nowString and a 1 hour expiry time" >> {
        s"I should get $defaultLatestFilename" >> {
          val latest = S3ApiProvider.latestUnexpiredDqZipFilename(None, nowProvider, oneHourMillis)

          latest === defaultLatestFilename
        }
      }

      s"Given an existing latest file name that has expired ($expiredFilename) and a 1 hour expiry time" >> {
        s"I should get $defaultLatestFilename" >> {
          val latest = S3ApiProvider.latestUnexpiredDqZipFilename(Option(expiredFilename), nowProvider, oneHourMillis)

          latest === defaultLatestFilename
        }
      }

      s"Given an existing latest file name that has not expired ($unexpiredFilename)" >> {
        s"I should get $unexpiredFilename" >> {
          val latest = S3ApiProvider.latestUnexpiredDqZipFilename(Option(unexpiredFilename), nowProvider, oneHourMillis)

          latest === unexpiredFilename
        }
      }
    }
  }
}
