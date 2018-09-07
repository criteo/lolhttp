package lol.http

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, KeyManagerFactory, X509TrustManager}


import org.http4s.blaze.util.BogusKeystore

/** lol SSL. */
object SSL {

  /** SSL configuration for clients.  */
  class ClientConfiguration private[http] (val ctx: SSLContext, name: String) {
    def engine = {
      val engine = ctx.createSSLEngine()
      engine.setUseClientMode(true)
      engine
    }
    override def toString = s"ClientConfiguration($name)"
  }

  /** SSL configuration for servers.  */
  class ServerConfiguration private[http] (val ctx: SSLContext, name: String) {
    def engine = {
      val engine = ctx.createSSLEngine()
      engine.setUseClientMode(false)
      engine
    }
    override def toString = s"ServerConfiguration($name)"
  }

  /** Provides the default client SSL configuration. */
  object ClientConfiguration {
    /** The default SSL configuration. */
    implicit lazy val default = new ClientConfiguration({
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, null, null)
      sslContext
    }, "default")
  }

  /** A "Trust all" client configuration that will accept any certificate.
    * You can use it as configuration for an HTTP client that need to connect to an
    * insecure server.
    */
  lazy val trustAll = new ClientConfiguration({
    val sslContext = SSLContext.getInstance("TLS")
    val trustAllCerts =
      new X509TrustManager() {
        def getAcceptedIssuers() = new Array[X509Certificate](0)
        def checkClientTrusted(certs: Array[X509Certificate], authType: String) = ()
        def checkServerTrusted(certs: Array[X509Certificate], authType: String) = ()
      }
    sslContext.init(null, Array(trustAllCerts), new SecureRandom())
    sslContext
  }, "trustAll")

  /** An SSL server configuration with a self-signed certificate.
    * You can use it to start an HTTPS server with an insecure certificate.
    */
  lazy val selfSigned = new ServerConfiguration({
    val ksStream = BogusKeystore.asInputStream()
    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)
    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(kmf.getKeyManagers(), null, null)
    sslContext
  }, "selfSigned")

}
