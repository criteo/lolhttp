# lolhttp

A scala HTTP server & client library.

## About

Servers and clients are service functions. A service takes an HTTP request and eventually returns an HTTP response. Requests and responses are just a set of HTTP headers and a content body. The content body is a lazy stream of bytes based on [fs2](https://github.com/functional-streams-for-scala/fs2), making it easy to handle streaming scenarios if needed. Yet, the library provides content encoders and decoders for the common scala types. All concepts are shared between servers and clients, making it simple to assemble them. For example writing a reverse proxy is just a matter of piping a client into a server. SSL is supported on both sides.

## Usage

The library is cross-built for __Scala 2.11__ and __Scala 2.12__. The core module to use is `"org.criteo.lolhttp" %% "lolhttp" % "0.3.0"`.

There are also 2 optional companion libraries:

- `"org.criteo.lolhttp" %% "loljson" % "0.3.0"`, provides integration with the [circe](https://circe.github.io/circe/) JSON library.
- `"org.criteo.lolhttp" %% "lolhtml" % "0.3.0"`, provides minimal HTML templating.

## Documentation

The [API documentation](http://g.bort.gitlab.preprod.crto.in/lolhttp/api/lol/index.html) is the main reference.

You can also follow this hands-on introduction using annotated example programs:

- [Hello world!](http://g.bort.gitlab.preprod.crto.in/lolhttp/examples/HelloWorld.scala.html)
- [Serving files from classpath](http://g.bort.gitlab.preprod.crto.in/lolhttp/examples/ServingFiles.scala.html)
- [Reading large request streams](http://g.bort.gitlab.preprod.crto.in/lolhttp/examples/LargeFileUpload.scala.html)
- [A simple reverse proxy](http://g.bort.gitlab.preprod.crto.in/lolhttp/examples/ReverseProxy.scala.html)
