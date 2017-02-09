
lazy val commonSettings = Seq(
  organization := "com.criteo",
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.11.8", "2.12.1"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-language:postfixOps",
    "-Xfuture",
    "-Ywarn-unused-import"
  ),
  fork in Test := true,

  // Useful to run flakey tests
  commands += Command.single("repeat") { (state, arg) =>
    arg :: s"repeat $arg" :: state
  },

  // Run example in another JVM, and quit on key press
  commands += Command.single("example") { (state, arg) =>
    s"examples/test:runMain TestExample $arg" :: state
  }
)

lazy val lolhttp =
  (project in file("core")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "0.9.2",
      "io.netty" % "netty-codec-http2" % "4.1.7.Final",
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.55",
      "org.bouncycastle" % "bcprov-jdk15on" % "1.55",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),

    // Vendorise internal libs
    assemblyShadeRules in assembly := Seq(
      ShadeRule.rename("io.netty.**" -> "lol.http.internal.@0").inAll,
      ShadeRule.rename("org.bouncycastle.**" -> "lol.http.internal.@0").inAll
    ),
    assemblyMergeStrategy in assembly := {
      case "META-INF/io.netty.versions.properties" =>
        MergeStrategy.first
      case x =>
        val defaultStrategy = (assemblyMergeStrategy in assembly).value
        defaultStrategy(x)
    },
    assemblyExcludedJars in assembly := {
      val cp = (fullClasspath in assembly).value
      cp.filter(_.data.getName.startsWith("fs2-"))
    },
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
    publishArtifact in (Compile, packageBin) := false,
    artifact in (Compile, assembly) := {
      val core = (artifact in (Compile, packageBin)).value
      val vendorised = (artifact in (Compile, assembly)).value
      vendorised
    }
  ).
  settings(addArtifact(artifact in (Compile, assembly), assembly): _*)

lazy val loljson =
  (project in file("json")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % "0.6.0") ++ Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    )
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val examples =
  project.
  settings(commonSettings: _*).
  settings(
    publishArtifact := false,
    fork in Test := true,
    connectInput in Test := true
  ).
  dependsOn(lolhttp, loljson)

lazy val root =
  (project in file(".")).
  settings(commonSettings: _*).
  settings(
    publishArtifact := false
  ).
  aggregate(lolhttp, loljson, examples)
