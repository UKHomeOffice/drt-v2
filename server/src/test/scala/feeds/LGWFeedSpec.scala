package feeds


import java.io.{ByteArrayInputStream, FileInputStream}
import java.nio.file.FileSystems
import java.security.KeyFactory
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.chroma.chromafetcher.ChromaFetcher.ChromaToken
import drt.chroma.chromafetcher.ChromaParserProtocol
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.opensaml.DefaultBootstrap
import org.opensaml.saml2.core.Assertion
import org.opensaml.saml2.core.impl._
import org.opensaml.xml.Configuration
import org.opensaml.xml.io.{Marshaller, MarshallerFactory}
import org.opensaml.xml.security.{DefaultSecurityConfigurationBootstrap, SecurityHelper}
import org.opensaml.xml.signature.Signer
import org.opensaml.xml.signature.impl.SignatureBuilder
import org.opensaml.xml.util.XMLHelper
import org.opensaml.xml.security.x509.BasicX509Credential
import org.specs2.mutable.SpecificationLike

import scala.collection.JavaConverters._
import org.w3c.dom.Element
import spray.http.{FormData, GenericHttpCredentials}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import ChromaParserProtocol._

import scala.concurrent.{Await, Future}

class LGWFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
  "PORT_CODE" -> "LGW",
  "feeds.gatwick.live.azure.name.id" -> "UKBF",
  "feeds.gatwick.live.azure.issuer" -> "UKBF",
  "feeds.gatwick.live.azure.namespace" -> "www.gatwick.co.uk"
).asJava))) with SpecificationLike {

  sequential
  isolated

  //  val tokenScope = s"http://${xxx}.servicebus.windows.net/partners/${yyy}/to"
  //  val httpPostUri = s"https://${xxx}-sb.accesscontrol.windows.net/v2/OAuth2-13"
  //  val acsTokenServiceGrant = "urn:oasis:names:tc:SAML:2.0:assertion"

  //http://stackoverflow.com/questions/11952274/how-can-i-create-keystore-from-an-existing-certificate-abc-crt-and-abc-key-fil


  object CredentialsFactory {

    /**
      * Builds a BasicX509Credential using PRIVATE_KEY and CERTIFICATE
      *
      * @return a BasicX509Credential
      */
    def getSigningCredential(privateKey: Array[Byte], certificate: Array[Byte]): BasicX509Credential = {
      val credential = new BasicX509Credential

      credential.setEntityCertificate(getCertificate(certificate))
      credential.setPrivateKey(getPrivateKey(privateKey))

      credential
    }

    /**
      * Loads in a private key from file
      *
      * @return a RSAPrivateKey representing the private key bytes
      */
    def getPrivateKey(privateKey: Array[Byte]): RSAPrivateKey = {
      val keyFactory = KeyFactory.getInstance("RSA")
      val ks = new PKCS8EncodedKeySpec(privateKey)
      keyFactory.generatePrivate(ks).asInstanceOf[RSAPrivateKey]
    }

    /**
      * Loads in a certificate from file
      *
      * @return the X509 certificate from this file
      */
    def getCertificate(certificate: Array[Byte]): X509Certificate = {
      val bis = new ByteArrayInputStream(certificate)

      try {
        CertificateFactory.getInstance("X.509").generateCertificate(bis).asInstanceOf[X509Certificate]
      } finally {
        IOUtils.closeQuietly(bis)
      }
    }
  }

  def createAzureSamlAssertionAsString(privateKey: Array[Byte], certificate: Array[Byte]): String = {
    val assertion = createAzureSamlAssertion(privateKey, certificate)

    // welcome to java and its horrendous mutating method magic. the following two lines
    // do something important to the signature
    Configuration.getMarshallerFactory.getMarshaller(assertion).marshall(assertion)
    Signer.signObject(assertion.getSignature)

    val marshaller = new ResponseMarshaller
    val plain = marshaller.marshall(assertion)

    XMLHelper.nodeToString(plain)
  }

  val config = system.settings.config

  def createAzureSamlAssertion(privateKey: Array[Byte], certificate: Array[Byte]): Assertion = {
    val builder: AssertionBuilder = new AssertionBuilder()
    val assertion = builder.buildObject()
    assertion.setID("_" + UUID.randomUUID().toString)
    assertion.setIssueInstant(new DateTime())

    val nameId = new NameIDBuilder().buildObject
    //    val config = ConfigFactory.load
    nameId.setValue(config.getString("feeds.gatwick.live.azure.name.id"))

    val subject = new SubjectBuilder().buildObject
    subject.setNameID(nameId)
    assertion.setSubject(subject)

    val subjectConfirmation = new SubjectConfirmationBuilder().buildObject
    subjectConfirmation.setMethod("urn:oasis:names:tc:SAML:2.0:cm:bearer")
    subject.getSubjectConfirmations.add(subjectConfirmation)

    val audience = new AudienceBuilder().buildObject
    audience.setAudienceURI("https://" + config.getString("feeds.gatwick.live.azure.namespace") + "-sb.accesscontrol.windows.net")

    val audienceRestriction = new AudienceRestrictionBuilder().buildObject
    audienceRestriction.getAudiences.add(audience)

    val conditions = new ConditionsBuilder().buildObject
    conditions.getConditions.add(audienceRestriction)
    assertion.setConditions(conditions)

    val issuer = new IssuerBuilder().buildObject
    issuer.setValue(config.getString("feeds.gatwick.live.azure.issuer"))
    assertion.setIssuer(issuer)

    signAssertion(assertion, privateKey, certificate)

    assertion
  }

  val security = DefaultSecurityConfigurationBootstrap.buildDefaultConfig
  def signAssertion(assertion: Assertion, privateKey: Array[Byte], certificate: Array[Byte]) {
    val signature = new SignatureBuilder().buildObject
    val signingCredential = CredentialsFactory.getSigningCredential(privateKey, certificate)
    signature.setSigningCredential(signingCredential)
    val secConfig = security //Configuration.getGlobalSecurityConfiguration
    println(s"secConfig $secConfig")
    SecurityHelper.prepareSignatureParams(signature, signingCredential, secConfig, null)
    assertion.setSignature(signature)
  }

  val GRANT = "urn:oasis:names:tc:SAML:2.0:assertion"


  "something" should {
    "do something" in {
      //      DefaultBootstrap.bootstrap()

      val certfilpath = "idahoconnect.drt.homeoffice.gov.uk.cert"
      val certificateURI = FileSystems.getDefault.getPath(certfilpath)

      if (!certificateURI.toFile.canRead) {
        throw new Exception(s"Could not read Gatwick certificate file from $certfilpath")
      }

      val privateKeyURI = FileSystems.getDefault.getPath("idahoconnect.drt.homeoffice.gov.uk.private-pkcs8.pem")

      if (!privateKeyURI.toFile.canRead) {
        throw new Exception(s"Could not read Gatwick private key file from /tmp/drt-lgw.pem")
      }

      val pkInputStream = new FileInputStream(privateKeyURI.toFile)
      val certInputStream = new FileInputStream(certificateURI.toFile)

      val privateKey = IOUtils.toByteArray(pkInputStream)
      val certificate = IOUtils.toByteArray(certInputStream)

      println(s"privateKey: $privateKey")
      println(s"certificate: $certificate")

      val assertion = createAzureSamlAssertionAsString(privateKey, certificate)

      assert(assertion.startsWith("""<?xml version="1.0" encoding="UTF-8"?><saml2:Assertion xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion" ID=""""))
      import spray.http.HttpHeaders.{Accept, Authorization}
      import spray.http.{HttpRequest, HttpResponse, MediaTypes, OAuth2BearerToken}
      import spray.httpx.SprayJsonSupport // intellij may try to remove this, don't let it or unmarshall will stop working

      val azureServiceNamespace = config.getString("feeds.gatwick.live.azure.namespace")
      val issuer = config.getString("feeds.gatwick.live.azure.issuer")
      val tokenScope = s"http://$azureServiceNamespace.servicebus.windows.net/partners/$issuer/to"
      val tokenPostUri = s"https://$azureServiceNamespace-sb.accesscontrol.windows.net/v2/OAuth2-13"

      val paramsAsForm = FormData(Map(
        "scope" -> tokenScope,
        "grant_type" -> GRANT,
        "assertion" -> assertion
      ))
      import spray.client.pipelining._
      import system.dispatcher

      val logRequest: HttpRequest => HttpRequest = { r => println(r); r }
      val logResponse: HttpResponse => HttpResponse = { r => println(r); r }

      val tokenPostPipeline = (
        addHeader(Accept(MediaTypes.`application/json`))
          ~> logRequest
          ~> sendReceive
        //~> unmarshal[ChromaToken]
        )

      val tokenPostResult = tokenPostPipeline(Post(tokenPostUri, paramsAsForm))

      import scala.concurrent.duration._

      val tokenResult = Await.result(tokenPostResult, 10 seconds)
      println(s"tokenResult $tokenResult")
      val restApiTimeout = 30 //seconds
      //      val token = tokenResult.entity.data.asString
      val serviceBusUri = s"https://${azureServiceNamespace}.servicebus.windows.net/partners/${issuer}/to/messages/head?timeout=$restApiTimeout"
      val wrapHeder = "WRAP access_token=\"" + tokenResult + "\""

      val sbResultFuture = (
        Post(serviceBusUri)
          ~> addHeader("Authorization", wrapHeder)
        )

      false

    }
  }
}