package drt.server.feeds.lgw

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

import org.apache.commons.io.IOUtils
import org.opensaml.xml.security.x509.BasicX509Credential


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
