package lol.http

import java.io.{ File }
import java.security.{ KeyStore }
import javax.net.ssl.{ TrustManagerFactory }

import io.netty.handler.ssl.{ SslContextBuilder }
import io.netty.handler.ssl.util.{
  InsecureTrustManagerFactory,
  SelfSignedCertificate }

/** lol SSL. */
object SSL {

  /** SSL configuration for clients.  */
  class ClientConfiguration private[http] (private[http] val builder: SslContextBuilder, name: String) {
    override def toString = s"ClientConfiguration($name)"
  }

  /** SSL configuration for servers.  */
  class ServerConfiguration private[http] (private[http] val builder: SslContextBuilder, name: String) {
    override def toString = s"ServerConfiguration($name)"
  }

  /** Provides the default client SSL configuration. */
  object ClientConfiguration {
    /** The default SSL configuration. */
    implicit lazy val default = new ClientConfiguration({
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      trustManagerFactory.init(null: KeyStore)
      SslContextBuilder.forClient.trustManager(trustManagerFactory)
    }, "default")
  }

  /** A "Trust all" client configuration that will accept any certificate.
    * You can use it as configuration for an HTTP client that need to connect to an
    * insecure server.
    */
  lazy val trustAll = new ClientConfiguration({
    SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE)
  }, "trustAll")

  /** Generate an SSL server configuration with a self-signed certificate.
    * You can use it to start an HTTPS server with an insecure certificate.
    * @param fqdn the fqdn to use for the certificate (default to localhost)
    */
  def selfSigned(fqdn: String = "localhost") = new ServerConfiguration({
    val ssc = new SelfSignedCertificate(fqdn)
    SslContextBuilder.forServer(ssc.certificate, ssc.privateKey)
  }, s"selfSigned for $fqdn")

  def serverCertificate(certificate: File, privateKey: File, privateKeyPassword: String): ServerConfiguration =
    new ServerConfiguration({
      SslContextBuilder.forServer(certificate, privateKey, privateKeyPassword)
    }, s"serverCertificate from $certificate")

  def serverCertificate(certificatePath: String, privateKeyPath: String, privateKeyPassword: String): ServerConfiguration =
    serverCertificate(new File(certificatePath), new File(privateKeyPath), privateKeyPassword)

}
