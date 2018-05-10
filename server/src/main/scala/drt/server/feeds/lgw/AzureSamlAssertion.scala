package drt.server.feeds.lgw

import java.util.UUID
import org.joda.time.DateTime
import org.opensaml.DefaultBootstrap
import org.opensaml.saml2.core.Assertion
import org.opensaml.saml2.core.impl._
import org.opensaml.xml.Configuration
import org.opensaml.xml.security.SecurityHelper
import org.opensaml.xml.signature.Signer
import org.opensaml.xml.signature.impl.SignatureBuilder
import org.opensaml.xml.util.XMLHelper

case class AzureSamlAssertion(namespace: String, issuer: String, nameId: String) {

  def initialiseOpenSAMLLibraryWithDefaultConfiguration(): Unit = DefaultBootstrap.bootstrap()

  initialiseOpenSAMLLibraryWithDefaultConfiguration()

  def asString(privateKey: Array[Byte], certificate: Array[Byte]): String = {
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
