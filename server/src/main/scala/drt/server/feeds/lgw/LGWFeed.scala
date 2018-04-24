package drt.server.feeds.lgw

import java.io.{ByteArrayInputStream, FileInputStream}
import java.nio.file.{FileSystems, Path}
import java.util.UUID
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision, ThrottleMode}
import drt.http.ProdSendAndReceive
import drt.server.feeds.lgw.LGWParserProtocol._
import drt.shared.Arrival
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.opensaml.DefaultBootstrap
import org.opensaml.saml2.core.Assertion
import org.opensaml.saml2.core.impl._
import org.opensaml.xml.Configuration
import org.opensaml.xml.security.SecurityHelper
import org.opensaml.xml.signature.Signer
import org.opensaml.xml.signature.impl.SignatureBuilder
import org.opensaml.xml.util.XMLHelper
import org.slf4j.{Logger, LoggerFactory}
import spray.client.pipelining
import spray.client.pipelining.{Delete, Post, addHeader, _}
import spray.http.HttpHeaders.Accept
import spray.http.{FormData, HttpRequest, HttpResponse, MediaTypes}
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.xml.Node

case class LGWFeed(certPath: String, privateCertPath: String, namespace: String, issuer: String, nameId: String, implicit val system: ActorSystem) extends ProdSendAndReceive {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def initialiseOpenSAMLLibraryWithDefaultConfiguration(): Unit = DefaultBootstrap.bootstrap()

  initialiseOpenSAMLLibraryWithDefaultConfiguration()

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
  val assertion: String = createAzureSamlAssertionAsString(privateKey, certificate)

  val tokenScope = s"http://$namespace.servicebus.windows.net/partners/$issuer/to"
  val tokenPostUri = s"https://$namespace-sb.accesscontrol.windows.net/v2/OAuth2-13"

  val GRANT = "urn:oasis:names:tc:SAML:2.0:assertion"

  implicit val executionContext =  system.dispatcher

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
    val restApiTimeoutInSeconds = 30
    val serviceBusUri = s"https://$namespace.servicebus.windows.net/partners/$issuer/to/messages/head?timeout=$restApiTimeoutInSeconds"
    val wrapHeader = "WRAP access_token=\"" + token.access_token + "\""

    val toArrivals: HttpResponse => List[Arrival] = { r =>
      log.debug(s"LGW response status code is ${r.status.intValue}")
      val is = new ByteArrayInputStream(r.entity.data.toByteArray)
      val xmlTry = Try (scala.xml.XML.load(is)).recoverWith{
        case e: Throwable => log.error(s"Cannot load Gatwick XML from the response ${r.status}", e); null
      }
      IOUtils.closeQuietly(is)
      val xmlSeq = xmlTry.map(scala.xml.Utility.trimProper(_)).get
      lazy val result = xmlSeq map nodeToArrival

      def nodeToArrival = (n: Node) => {

        val terminal = (n \\ "AirportResources" \ "Resource").find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "AircraftTerminal" text).getOrElse("")
        val mappedTerminal = terminal match {
          case "1" => "S"
          case "2" => "N"
          case _ => ""
        }
        val arrival = new Arrival(
          Operator = (n \ "AirlineIATA") text,
          Status = parseStatus(n),
          EstDT = parseDateTime(n, "TDN", "EST").getOrElse(""),
          ActDT = parseDateTime(n, "TDN", "ACT").getOrElse(""),
          EstChoxDT = parseDateTime(n, "OBN", "EST").getOrElse(""),
          ActChoxDT = parseDateTime(n, "OBN", "ACT").getOrElse(""),
          Gate = (n \\ "PassengerGate").headOption.map(n => n text).getOrElse(""),
          Stand = (n \\ "ArrivalStand").headOption.map(n => n text).getOrElse(""),
          MaxPax = (n \\ "SeatCapacity").headOption.map(n => (n text).toInt).getOrElse(0),
          ActPax = parsePaxCount(n, "70A").getOrElse(0),
          TranPax = parsePaxCount(n, "TIP").getOrElse(0),
          RunwayID = parseRunwayId(n).getOrElse(""),
          BaggageReclaimId = Try(n \\ "FIDSBagggeHallActive" text).getOrElse(""),
          FlightID = 0,
          AirportID = "LGW",
          Terminal = mappedTerminal,
          rawICAO = (n \\ "AirlineICAO" text) + parseFlightNumber(n),
          rawIATA = (n \\ "AirlineIATA" text) + parseFlightNumber(n),
          Origin = parseOrigin(n),
          SchDT = (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text ).equals("SCT") ).map(n=>n text).getOrElse(""),
          Scheduled = (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text ).equals("SCT") ).map(n=> services.SDate.parseString(n text).millisSinceEpoch).getOrElse(0),
          PcpTime = 0,
          LastKnownPax = None)
        log.info(s"parsed arrival: $arrival")
        arrival
      }

      result.toList
    }

    val resultPipeline: pipelining.WithTransformerConcatenation[HttpRequest, Future[List[Arrival]]] = (
      addHeader("Authorization", wrapHeader)
        ~> sendAndReceive
        ~> toArrivals
      )

    resultPipeline(Delete(serviceBusUri))
    .recoverWith {
      case t: Throwable =>
        log.warn(s"Failed to get Flight details: ${t.getMessage}", t)
        Future(List.empty)
    }
  }

  private def parseFlightNumber(n: Node) = {
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "FlightNumber" text
  }

  def parseStatus(n: Node): String = {
    ((n \ "FlightLeg").head \ "LegData").head \ "OperationalStatus" text
  }

  def parseOrigin(n: Node): String = {
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "DepartureAirport" text
  }

  def parseRunwayId(n: Node): Option[String] = {
    (n \\ "AirportResources" \ "Resource").find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "Runway" text)
  }

  def parsePaxCount(n: Node, qualifier: String): Option[Int] = {
    (n \\ "PaxCount").find(n => (n \ "@Qualifier" text).equals(qualifier) && (n \ "@Class").isEmpty).map(n => (n text).toInt)
  }

  def parseDateTime(n: Node, operationQualifier: String, timeType: String): Option[String] = {
    (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text ).equals("SCT") ).map(n=>n text)
    (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals(operationQualifier) && (n \ "@TimeType" text).equals(timeType)).map(n => n text)
  }

  def createAzureSamlAssertionAsString(privateKey: Array[Byte], certificate: Array[Byte]): String = {
    val assertion = createAzureSamlAssertion(privateKey, certificate)

    def marshalledAndSignedMessage = {
      Configuration.getMarshallerFactory.getMarshaller(assertion).marshall(assertion)
      Signer.signObject(assertion.getSignature)

      val marshaller = new ResponseMarshaller
      marshaller.marshall(assertion)
    }

    XMLHelper.nodeToString(marshalledAndSignedMessage)
  }

  def createAzureSamlAssertion(privateKey: Array[Byte], certificate: Array[Byte]): Assertion = {
    val builder: AssertionBuilder = new AssertionBuilder()
    val assertion = builder.buildObject()
    assertion.setID("_" + UUID.randomUUID().toString)
    assertion.setIssueInstant(new DateTime())

    val nameIdObj = new NameIDBuilder().buildObject
    nameIdObj.setValue(nameId)

    val subject = new SubjectBuilder().buildObject
    subject.setNameID(nameIdObj)
    assertion.setSubject(subject)

    val subjectConfirmation = new SubjectConfirmationBuilder().buildObject
    subjectConfirmation.setMethod("urn:oasis:names:tc:SAML:2.0:cm:bearer")
    subject.getSubjectConfirmations.add(subjectConfirmation)

    val audience = new AudienceBuilder().buildObject
    audience.setAudienceURI("https://" + namespace + "-sb.accesscontrol.windows.net")

    val audienceRestriction = new AudienceRestrictionBuilder().buildObject
    audienceRestriction.getAudiences.add(audience)

    val conditions = new ConditionsBuilder().buildObject
    conditions.getConditions.add(audienceRestriction)
    assertion.setConditions(conditions)

    val issuerObj = new IssuerBuilder().buildObject
    issuerObj.setValue(issuer)
    assertion.setIssuer(issuerObj)

    signAssertion(assertion, privateKey, certificate)

    assertion
  }

  def signAssertion(assertion: Assertion, privateKey: Array[Byte], certificate: Array[Byte]) {
    val signature = new SignatureBuilder().buildObject
    val signingCredential = CredentialsFactory.getSigningCredential(privateKey, certificate)
    signature.setSigningCredential(signingCredential)
    val secConfig = Configuration.getGlobalSecurityConfiguration
    SecurityHelper.prepareSignatureParams(signature, signingCredential, secConfig, null)
    assertion.setSignature(signature)
  }
}

object LGWFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)
  var tokenFuture: Future[GatwickAzureToken] = _

  def apply()(implicit actorSystem: ActorSystem): Source[Seq[Arrival], Cancellable] = {
    val config = actorSystem.settings.config

    implicit val dispatcher: ExecutionContextExecutor =  actorSystem.dispatcher

    val certPath = config.getString("feeds.gatwick.live.azure.cert")
    val privateCertPath = config.getString("feeds.gatwick.live.azure.private_cert")
    val azureServiceNamespace = config.getString("feeds.gatwick.live.azure.namespace")
    val issuer = config.getString("feeds.gatwick.live.azure.issuer")
    val nameId = config.getString("feeds.gatwick.live.azure.name.id")

    val feed = LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, actorSystem)

    val pollFrequency = 3 seconds
    val initialDelayImmediately: FiniteDuration = 1 milliseconds

    tokenFuture = feed.requestToken()

    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .throttle(elements = 1, per = 30 seconds, maximumBurst = 1, ThrottleMode.shaping)
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
      .map((_) => {
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
