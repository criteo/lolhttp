package lol.http

import java.util.{ Calendar }
import java.math.{ BigInteger }

import java.security.{ KeyStore, Security, KeyPairGenerator, SecureRandom }
import javax.net.ssl.{ SSLContext, KeyManagerFactory, TrustManagerFactory }

import javax.net.ssl.{ X509TrustManager }
import java.security.cert.{ X509Certificate }

import org.bouncycastle.asn1.x500.{ X500Name }
import org.bouncycastle.asn1.x509.{ SubjectPublicKeyInfo }
import org.bouncycastle.cert.{ X509v3CertificateBuilder }
import org.bouncycastle.operator.jcajce.{ JcaContentSignerBuilder }
import org.bouncycastle.cert.jcajce.{ JcaX509CertificateConverter }
import org.bouncycastle.jce.provider.{ BouncyCastleProvider }

/** lol SSL. */
object SSL {

  Security.addProvider(new BouncyCastleProvider)

  /** An SSL configuration.
    * @param ctx the `javax.net.ssl.SSLContext` to use.
    */
  case class Configuration(ctx: SSLContext)

  /** Provides the default SSL configuration. */
  object Configuration {

    /** The default SSL configuration based on the default JVM `SSLContext`. */
    implicit val default = Configuration(SSLContext.getDefault)
  }

  /** A "Trust all" configuration that will accept any certificate.
    * You can use it as configuration for an HTTP client that need to connect to an
    * insecure server.
    */
  lazy val trustAll = Configuration {
    val trustAll = new X509TrustManager {
      def getAcceptedIssuers() = null
      def checkClientTrusted(certs: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(cert: Array[X509Certificate], authType: String): Unit = ()
    }
    val ssl = SSLContext.getInstance("SSL")
    ssl.init(null, Array(trustAll), null)
    ssl
  }

  /** Generate an SSL configuration with a self-signed certificate.
    * You can use it to start an HTTPS server with an insecure certificate.
    */
  def selfSigned(hostname: String = "localhost") = {
    val password = Array.empty[Char]
    val keys = newKeyPair()
    val issuer = new X500Name(s"CN=$hostname")
    val certificate = new JcaX509CertificateConverter().getCertificate {
      new X509v3CertificateBuilder(
        issuer,
        BigInteger.valueOf(new SecureRandom().nextInt),
        date(),
        date(Calendar.YEAR -> 1),
        issuer,
        SubjectPublicKeyInfo.getInstance(keys.getPublic.getEncoded)
      ).build(
        new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keys.getPrivate)
      )
    }
    val keyStore = {
      val ks = emptyKeyStore(password)
      ks.setKeyEntry("private", keys.getPrivate, password, Array(certificate))
      ks.setCertificateEntry("cert", certificate)
      ks
    }
    val keyManager = {
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      factory.init(keyStore, password)
      factory.getKeyManagers
    }
    val trustManager = {
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      factory.init(keyStore)
      factory.getTrustManagers
    }
    Configuration {
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManager, trustManager, new SecureRandom)
      sslContext
    }
  }

  private def emptyKeyStore(password: Array[Char]) = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, password)
    keyStore
  }

  private def newKeyPair(size: Int = 1024) = {
    val keys = KeyPairGenerator.getInstance("RSA", "BC")
    keys.initialize(size, new SecureRandom)
    keys.generateKeyPair()
  }

  private def date(offset: (Int,Int) = (Calendar.YEAR, 0)) = {
    val cal = Calendar.getInstance
    (cal.add _).tupled(offset)
    cal.getTime
  }

}
