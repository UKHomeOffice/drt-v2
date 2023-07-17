package controllers

import akka.stream.scaladsl.StreamConverters
import play.api.http.HttpEntity
import play.api.mvc.{InjectedController, ResponseHeader, Result}
import play.api.{Configuration, Environment}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters._

class S3FileController @Inject()(implicit val config: Configuration, env: Environment, ec: ExecutionContext) extends InjectedController {

  val accessKey = config.getOptional[String]("s3.data.credentials.access_key_id").getOrElse("")
  val secretKey = config.getOptional[String]("s3.data.credentials.secret_key").getOrElse("")
  val bucketName = config.getOptional[String]("s3.data.bucket-name").getOrElse("")
  val prefixFolder = config.getOptional[String]("s3.data.feature-guides-folder-prefix").getOrElse("")

  val credentialsProvider = StaticCredentialsProvider
    .create(AwsBasicCredentials.create(accessKey, secretKey))

  val s3Client: S3AsyncClient =
    S3AsyncClient
      .builder()
      .region(Region.EU_WEST_2)
      .credentialsProvider(credentialsProvider)
      .build()


  def getFile(fileName: String) = Action.async {
    val getObjectRequest: GetObjectRequest =
      GetObjectRequest
        .builder()
        .bucket(bucketName)
        .key(s"$prefixFolder/$fileName")
        .build()


    s3Client
      .getObject(getObjectRequest, AsyncResponseTransformer.toBytes[GetObjectResponse]).asScala
      .map { response =>
        val contentType = response.response().contentType()
        Result(
          header = ResponseHeader(OK, Map("Content-Type" -> contentType)),
          body = HttpEntity.Streamed(StreamConverters.fromInputStream(() => response.asInputStream()), None, Some(contentType))
        )
      }
  }
}
