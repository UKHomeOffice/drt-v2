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
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.Scope

class S3ApiProviderSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  trait Context extends Scope  {
    val s3ClientMock: AmazonS3Client = mock[AmazonS3Client]
    val awsCredentialsMock: AWSCredentials = mock[AWSCredentials]
    val s3ApiProvider = new S3ApiProvider(awsCredentialsMock, "") {
      override def s3Client: AmazonS3Client = s3ClientMock
    }
  }

  "Can continue if there is an error getting a file from s3" in new Context  {
    val list = List(ByteString(""), null)
    val iterator = list.iterator
    val source: Source[ByteString, NotUsed] = Source.fromIterator(() => iterator)
    val result = s3ApiProvider.fileNameAndContentFromZip("drt_dq_181108_000233_2957.zip", source)

    result must be_==(("drt_dq_181108_000233_2957.zip", List.empty))
  }

}
