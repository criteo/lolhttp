<img src="https://criteo.github.io/lolhttp/images/lolhttp.png" width="70">

# lolhttp

A scala HTTP & HTTP/2 server and client library.

## About the library

Both server and client are plain functions accepting an HTTP request and eventually giving back an HTTP response. Requests and responses are just HTTP metadata along with a lazy content body. The content body is a stream of bytes based on [fs2](https://github.com/functional-streams-for-scala/fs2), making it easy to handle streaming scenarios if needed. For additional convenience, the library provides content encoders and decoders for the common scala types. SSL is supported on both sides.

## Hello World

```scala
// Let's start an HTTP server
Server.listen(8888) {
  case GET at "/hello" =>
    Ok("Hello World!")
  case _ =>
    NotFound
}

// Let's connect with an HTTP client
Client.run(Get("http://localhost:8888/hello")) { res =>
  res.readAs[String].map { contentBody =>
    println(s"Received: $contentBody")
  }
}
```

## About HTTP/2 support

HTTP/2 is supported on both server and client side. If SSL is enabled, the protocol negociation is done using ALPN. On plain connections however HTTP/2 is only supported with prior knowledge (clear text upgrade from HTTP/1.1 to HTTP/2 is ignored). Because of ALPN, HTTP/2 support over SSL requires running on Java 9 (_Running on Java 8 is still possible but you need to replace the default Java TLS implementation; see http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-starting_).

## Usage

The library is cross-built for __Scala 2.11__ and __Scala 2.12__.

The core module to use is `"com.criteo.lolhttp" %% "lolhttp" % "0.7.3"`.

There are also 2 optional companion libraries:

- `"com.criteo.lolhttp" %% "loljson" % "0.7.3"`, provides integration with the [circe](https://circe.github.io/circe/) JSON library.
- `"com.criteo.lolhttp" %% "lolhtml" % "0.7.3"`, provides minimal HTML templating.

## Documentation

The [API documentation](https://criteo.github.io/lolhttp/api/lol/index.html) is the main reference. If you need to access the underlying content stream, you should first have a look a the [fs2 documentation](https://github.com/functional-streams-for-scala/fs2) to understand the basics.

For those who prefer documentation by example, you can also follow these hands-on introductions:

- [Hello world!](https://criteo.github.io/lolhttp/examples/HelloWorld.scala.html)
- [Serving files from classpath](https://criteo.github.io/lolhttp/examples/ServingFiles.scala.html).
- [A Github API client](https://criteo.github.io/lolhttp/examples/GithubClient.scala.html).
- [A JSON web service](https://criteo.github.io/lolhttp/examples/JsonWebService.scala.html).
- [Reading large request streams](https://criteo.github.io/lolhttp/examples/LargeFileUpload.scala.html).
- [A simple reverse proxy](https://criteo.github.io/lolhttp/examples/ReverseProxy.scala.html).
- [An HTTP/2 server](https://criteo.github.io/lolhttp/examples/Http2Server.scala.html).

## License

This project is licensed under the Apache 2.0 license.

## Copyright

Copyright © Criteo, 2017.
