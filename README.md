<img src="https://criteo.github.io/lolhttp/images/lolhttp.png" width="70">

# lolhttp

A scala HTTP server & client library.

## About

Servers and clients are service functions. A service takes an HTTP request and eventually returns an HTTP response. Requests and responses are a set of HTTP headers along with a content body. The content body is a lazy stream of bytes based on [fs2](https://github.com/functional-streams-for-scala/fs2), making it easy to handle streaming scenarios if needed. Yet, the library provides content encoders and decoders for the common scala types. All concepts are shared between servers and clients, making it simple to compose them. SSL is supported on both sides.

## Usage

The library is cross-built for __Scala 2.11__ and __Scala 2.12__.

The core module to use is `"com.criteo.lolhttp" %% "lolhttp" % "0.3.2"`.

There are also 2 optional companion libraries:

- `"com.criteo.lolhttp" %% "loljson" % "0.3.2"`, provides integration with the [circe](https://circe.github.io/circe/) JSON library.
- `"com.criteo.lolhttp" %% "lolhtml" % "0.3.2"`, provides minimal HTML templating.

## Documentation

The [API documentation](https://criteo.github.io/lolhttp/api/lol/index.html) is the main reference.

You can also follow this hands-on introduction using annotated example programs:

- [Hello world!](https://criteo.github.io/lolhttp/examples/HelloWorld.scala.html)
- [Serving files from classpath](https://criteo.github.io/lolhttp/examples/ServingFiles.scala.html)
- [Reading large request streams](https://criteo.github.io/lolhttp/examples/LargeFileUpload.scala.html)
- [A simple reverse proxy](https://criteo.github.io/lolhttp/examples/ReverseProxy.scala.html)

## License

This project is licensed under the Apache 2.0 license.

## Copyright

Copyright © Criteo, 2017.
