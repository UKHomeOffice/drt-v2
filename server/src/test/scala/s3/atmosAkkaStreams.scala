package s3

import java.util.Date

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.S3ClientOptions
import org.specs2.mutable.SpecificationLike

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class atmosAkkaStreams extends TestKit(ActorSystem()) with SpecificationLike {
  test =>
  // atmos is skyscape/ ukcloud's substitute for Amazon's S3. We're not currently allowed to use s3 because of an
  // out of country restriction by the DataOwner.

  import com.mfglabs.commons.aws.s3._

  //  import ch.qos.logback.classic.Level
  //  import ch.qos.logback.classic.Logger
  import java.util.logging.{Level, Logger}

  val log = Logger.getLogger("")
  log.setLevel(Level.ALL)

  println(RegionUtils.getRegions().asScala.mkString("\n"))
  val bucket: String = "drtdqprod"
  val key = ""
  val prefix = ""
  private val configuration: ClientConfiguration = new ClientConfiguration()
  //    configuration.setSignerOverride("NoOpSignerType")
  configuration.setSignerOverride("S3SignerType")
  private val provider: ProfileCredentialsProvider = new ProfileCredentialsProvider("drt-atmos")
  private val s3client: AmazonS3AsyncClient = new AmazonS3AsyncClient(provider, configuration)
  s3client.client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
  private val skyscapeAtmosHost: String = "cas00003.skyscapecloud.com:8443"

  //  s3client.client.setRegion(Region.getRegion(""))
  //  val coptions = s3client

  s3client.client.setEndpoint(skyscapeAtmosHost)
  val builder = S3StreamBuilder(s3client)

  // contains un-materialized composable Source / Flow / Sink
  implicit val flowMaterializer = ActorMaterializer()

  val fileStream: Source[ByteString, NotUsed] = builder.getFileAsStream(bucket, key)

  //  val multipartfileStream: Source[ByteString, NotUsed] = builder.getMultipartFileAsStream(bucket, prefix)

  //  someBinaryStream.via(
  //    builder.uploadStreamAsFile(bucket, key, chunkUploadConcurrency = 2)
  //  )
  //
  //  someBinaryStream.via(
  //    builder.uploadStreamAsMultipartFile(
  //      bucket,
  //      prefix,
  //      nbChunkPerFile = 10000,
  //      chunkUploadConcurrency = 2
  //    )
  //  )

  val ops = new builder.MaterializedOps(flowMaterializer) // contains materialized methods on top of S3Stream
  ops.client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
  ops.client.setEndpoint(skyscapeAtmosHost)

  "skyscapeAtmosHostgiven an ops" >> {
    "we can list files" in {
      val files: Future[Seq[(String, Date)]] = ops.listFiles(bucket)
      val res = Await.result(files, 200 seconds)
      println("The files are!", res)
      println(s"There were ${res.length}")
      res.nonEmpty
    }
//    "list the other way " in {
//
//      val client = new AmazonS3Client(provider, configuration)
//      client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
//      client.setEndpoint(skyscapeAtmosHost)
//      client.listBuckets()
//      for (b <- client.listObjects(bucket).getObjectSummaries().asScala) {
//        println(b)
//      }
//      false
//    }
    //    "can heyey unzip content of a file" in {
    //      val fileNameStream: Source[(String, Date), NotUsed] = builder.listFilesAsStream(bucket)
    //      val res: Future[Done] = fileNameStream
    //        .filterNot(_._1.startsWith("PRE"))
    //        .runWith(Sink.foreach {
    //          println
    //        })
    //      Await.result(res, 3 seconds)
    //      true
    //    }
    //    "flatMap demo" in {
    //      val result = List(1, 2, 3).map(_ * 2)
    //      println(result)
    //      true
    //    }

    //    "can unzip content of a file" in {
    //      val poller = new SimpleS3Poller with CoreActors {
    //        implicit def system = test.system
    //
    //      }
    //
    ////      val res = poller.zipFiles.runWith(Sink.foreach {
    ////        (unzippedFileContent) => println(unzippedFileContent)
    ////      })
    //      val res = poller.streamAllThis
    ////      Await.result(res, 500 seconds)
    //      Thread.sleep(3000)
    //      true
    //    }
  }

  val file: Future[ByteString] = ops.getFile(bucket, key)

}
