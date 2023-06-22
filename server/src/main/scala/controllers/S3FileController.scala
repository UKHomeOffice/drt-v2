package controllers

import akka.stream.IOResult
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import play.api.ConfigLoader.configLoader
import play.api.http.HttpEntity
import play.api.http.Status.OK
import play.api.mvc.{InjectedController, ResponseHeader, Result}
import play.api.{Configuration, Environment}
import play.mvc.Controller
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}

import java.io.InputStream
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._

class S3FileController @Inject()(implicit val config: Configuration, env: Environment, ec: ExecutionContext) extends InjectedController {

  val accessKey = config.getOptional[String]("s3.data.credentials.access_key_id").getOrElse("")
  val secretKey = config.getOptional[String]("s3.data.credentials.secret_key").getOrElse("")
  val bucketName = config.getOptional[String]("s3.data.bucket-name").getOrElse("")
  val prefixFolder = config.getOptional[String]("s3.data.prefix-folder").getOrElse("")

  val credentialsProvider = StaticCredentialsProvider
    .create(AwsBasicCredentials.create(accessKey, secretKey))

  val s3Client: S3AsyncClient = S3AsyncClient.builder()
    .region(Region.EU_WEST_2) // Set the appropriate AWS region
    .credentialsProvider(credentialsProvider)
    .build()


  def getFile(fileName: String) = Action.async {
    val getObjectRequest: GetObjectRequest = GetObjectRequest.builder()
      .bucket(bucketName)
      .key(s"$prefixFolder/$fileName")
      .build()

    val responseBytes: Future[ResponseBytes[GetObjectResponse]] = s3Client
      .getObject(getObjectRequest, AsyncResponseTransformer.toBytes[GetObjectResponse]
      ).asScala

    val s3Response: Future[InputStream] = responseBytes.map(_.asInputStream())

    val contentTypeF: Future[String] = responseBytes.map(_.response().contentType())

    val responseTransformer: Future[Source[ByteString, Future[IOResult]]] =
      s3Response.map { inputStream =>
        StreamConverters.fromInputStream(() => inputStream)
      }

    val result = contentTypeF.map { contentType =>
      Result(
        header = ResponseHeader(OK, Map("Content-Type" -> contentType)),
        body = HttpEntity.Streamed(Source.futureSource(responseTransformer), None, Some(contentType))
      )
    }

    result
  }
}
