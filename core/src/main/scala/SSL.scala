package lol.http

import java.security.{ KeyStore }
import javax.net.ssl.{ TrustManagerFactory }

import io.netty.buffer.{ ByteBufAllocator }
import io.netty.handler.ssl.{ SslContext, SslContextBuilder }
import io.netty.handler.ssl.util.{ InsecureTrustManagerFactory, SelfSignedCertificate }

/** lol SSL. */
object SSL {

  private[http] class NettySslContext(private val ctx: SslContext) {
    def newHandler(alloc: ByteBufAllocator) = ctx.newHandler(alloc)
  }

  /** SSL configuration for clients.  */
  class ClientConfiguration private[http] (private[http] val ctx: NettySslContext, name: String) {
    override def toString = s"ClientConfiguration($ctx, $name)"
  }

  /** SSL configuration for servers.  */
  class ServerConfiguration private[http] (private[http] val ctx: NettySslContext, name: String) {
    override def toString = s"ServerConfiguration($ctx, $name)"
  }

  /** Provides the default client SSL configuration. */
  object ClientConfiguration {
    /** The default SSL configuration. */
    implicit lazy val default = new ClientConfiguration({
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      trustManagerFactory.init(null: KeyStore)
      new NettySslContext(SslContextBuilder.forClient.trustManager(trustManagerFactory).build())
    }, "default")
  }

  /** A "Trust all" client configuration that will accept any certificate.
    * You can use it as configuration for an HTTP client that need to connect to an
    * insecure server.
    */
  lazy val trustAll = new ClientConfiguration({
    new NettySslContext(SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE).build())
  }, "trustAll")

  /** Generate an SSL server configuration with a self-signed certificate.
    * You can use it to start an HTTPS server with an insecure certificate.
    * @param fqdn the fqdn to use for the certificate (default to localhost)
    */
  def selfSigned(fqdn: String = "localhost") = new ServerConfiguration({
    val ssc = new SelfSignedCertificate(fqdn)
    new NettySslContext(SslContextBuilder.forServer(ssc.certificate, ssc.privateKey).build())
  }, s"selfSigned for $fqdn")

}
