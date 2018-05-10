package drt.server.feeds.lgw

import java.io.FileInputStream
import java.nio.file.{FileSystems, Path}
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import drt.http.ProdSendAndReceive
import drt.server.feeds.lgw.LGWParserProtocol._
import drt.shared.Arrival
import org.apache.commons.io.IOUtils
import org.slf4j.{Logger, LoggerFactory}
import spray.client.pipelining
import spray.client.pipelining.{Delete, Post, addHeader, _}
import spray.http.HttpHeaders.Accept
import spray.http._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class LGWFeed(certPath: String, privateCertPath: String, namespace: String, issuer: String, nameId: String, implicit val system: ActorSystem) extends ProdSendAndReceive {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val privateCert: Path = FileSystems.getDefault.getPath(privateCertPath)
  val certificateURI: Path = FileSystems.getDefault.getPath(certPath)

  if (!certificateURI.toFile.canRead) {
    throw new Exception(s"Could not read Gatwick certificate file from $certPath")
  }

  if (!privateCert.toFile.canRead) {
    throw new Exception(s"Could not read Gatwick private key file from $privateCertPath")
  }

  val pkInputStream = new FileInputStream(privateCert.toFile)
  val certInputStream = new FileInputStream(certificateURI.toFile)

  val privateKey: Array[Byte] = IOUtils.toByteArray(pkInputStream)
  val certificate: Array[Byte] = IOUtils.toByteArray(certInputStream)

  lazy val assertion: String = AzureSamlAssertion(namespace = namespace, issuer = issuer, nameId = nameId).asString(privateKey, certificate)

  val tokenScope = s"http://$namespace.servicebus.windows.net/partners/$issuer/to"
  val tokenPostUri = s"https://$namespace-sb.accesscontrol.windows.net/v2/OAuth2-13"

  val GRANT = "urn:oasis:names:tc:SAML:2.0:assertion"

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  def requestToken(): Future[GatwickAzureToken] = {
    val paramsAsForm = FormData(Map(
      "scope" -> tokenScope,
      "grant_type" -> GRANT,
      "assertion" -> assertion
    ))

    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val tokenPostPipeline = (
      addHeader(Accept(MediaTypes.`application/json`))
        ~> sendAndReceive
        ~> unmarshal[GatwickAzureToken]
      )

    tokenPostPipeline(Post(tokenPostUri, paramsAsForm)).recoverWith {
      case t: Throwable =>
        log.warn(s"Failed to get GatwickAzureToken: ${t.getMessage}")
        Future(GatwickAzureToken("unknown", "unknown", "0", "unknown"))
    }
  }

  def requestArrivals(token: GatwickAzureToken): Future[List[Arrival]] = {
    val restApiTimeoutInSeconds = 25
    val serviceBusUri = s"https://$namespace.servicebus.windows.net/partners/$issuer/to/messages/head?timeout=$restApiTimeoutInSeconds"
    val wrapHeader = "WRAP access_token=\"" + token.access_token + "\""

    val toArrivals: HttpResponse => List[(Arrival, Option[String])] = { r =>

      r.status match {
        case StatusCodes.OK | StatusCodes.Created =>
          val locationOption: Option[String] = r.headers.find(h => h.name == "Location").map(_.value)
          ResponseToArrivals(r.entity.data.toByteArray, locationOption).getArrivals
        case status =>
          log.info(s"LGW response status is $status")
          List.empty[(Arrival, Option[String])]
      }
    }

    val processDeleteIfApplicable: List[(Arrival, Option[String])] => List[Arrival] = { arrivalsAndLocationList =>
      val deletePipeline = (
        addHeader("Authorization", wrapHeader)
          ~> sendAndReceive
        )
      arrivalsAndLocationList.flatMap(_._2).foreach { location =>
        deletePipeline(Delete(location)).recoverWith {
          case t: Throwable =>
            log.warn(s"Failed to send a Delete request to url $location", t)
            Future(location)
        }
      }

      arrivalsAndLocationList.map(_._1)
    }

    val resultPipeline: pipelining.WithTransformerConcatenation[HttpRequest, Future[List[Arrival]]] = (
      addHeader("Authorization", wrapHeader)
        ~> sendAndReceive
        ~> toArrivals
        ~> processDeleteIfApplicable
      )

    println(wrapHeader)
    println(serviceBusUri)
    resultPipeline(Post(serviceBusUri))
      .recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Flight details: ${t.getMessage}", t)
          Future(List.empty)
      }
  }

}

object LGWFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)
  var tokenFuture: Future[GatwickAzureToken] = _

  def apply()(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {
    val config = actorSystem.settings.config

    implicit val dispatcher: ExecutionContextExecutor = actorSystem.dispatcher

    val certPath = config.getString("feeds.gatwick.live.azure.cert")
    val privateCertPath = config.getString("feeds.gatwick.live.azure.private_cert")
    val azureServiceNamespace = config.getString("feeds.gatwick.live.azure.namespace")
    val issuer = config.getString("feeds.gatwick.live.azure.issuer")
    val nameId = config.getString("feeds.gatwick.live.azure.name.id")

    val feed = LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, actorSystem)

    val pollInterval = 100 milliseconds
    val initialDelayImmediately: FiniteDuration = 1 milliseconds

    tokenFuture = feed.requestToken()

    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollInterval, NotUsed)
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
      .map(_ => {
        Try {
          val arrivalsFuture = for {
            token <- tokenFuture
            arrivals <- feed.requestArrivals(token)
          } yield arrivals
          Await.result(arrivalsFuture, 30 seconds)
        } match {
          case Success(arrivals) =>
            log.info(s"Got Some Arrivals $arrivals")
            if (arrivals.isEmpty) {
              log.info(s"Empty LGW arrivals. Re-requesting token.")
              tokenFuture = feed.requestToken()
            }
            arrivals
          case Failure(t) =>
            log.info(s"Failed to fetch LGW arrivals. Re-requesting token. $t")
            tokenFuture = feed.requestToken()
            List.empty[Arrival]
        }
      })

    tickingSource
  }
}
