val VERSION = "0.13.1"

lazy val commonSettings = Seq(
  organization := "com.criteo.lolhttp",
  version := VERSION,
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value),
  scalacOptions ++= {
    Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-language:existentials",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xlint",
      "-Xlint:-inaccessible",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Xfuture",
      "-Ywarn-unused-import"
    ) ++ (
      if(scalaVersion.value.startsWith("2.11"))
        // 2.11.x options
        Nil
      else
        Seq("-Ywarn-macros:after")
    )
  },

  // Tests
  fork in Test := true,

  // Useful to run flakey tests
  commands += Command.single("repeat") { (state, arg) =>
    arg :: s"repeat $arg" :: state
  },

  // Run example in another JVM, and quit on key press
  commands += Command.single("runExample") { (state, arg) =>
    s"examples/it:runMain lol.http.examples.ExamplesTests $arg" :: state
  },

  // Maven config
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    "criteo-oss",
    sys.env.getOrElse("SONATYPE_PASSWORD", "")
  ),
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  pgpPassphrase := sys.env.get("SONATYPE_PASSWORD").map(_.toArray),
  pgpSecretRing := file(".travis/secring.gpg"),
  pgpPublicRing := file(".travis/pubring.gpg"),
  pomExtra in Global := {
    <url>https://github.com/criteo/lolhttp</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      </license>
    </licenses>
    <scm>
      <connection>scm:git:github.com/criteo/lolhttp.git</connection>
      <developerConnection>scm:git:git@github.com:criteo/lolhttp.git</developerConnection>
      <url>github.com/criteo/lolhttp</url>
    </scm>
    <developers>
      <developer>
        <name>Guillaume Bort</name>
        <email>g.bort@criteo.com</email>
        <url>https://github.com/guillaumebort</url>
        <organization>Criteo</organization>
        <organizationUrl>http://www.criteo.com</organizationUrl>
      </developer>
    </developers>
  }
)

def removeDependencies(groups: String*)(xml: scala.xml.Node) = {
  import scala.xml._
  import scala.xml.transform._
  (new RuleTransformer(
    new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match {
        case dependency @ Elem(_, "dependency", _, _, _*) =>
          if(dependency.child.collect { case e: Elem => e}.headOption.exists { e =>
            groups.exists(group => e.toString == s"<groupId>$group</groupId>")
          }) Nil else dependency
        case x => x
      }
    }
  ))(xml)
}

val circeVersion = "0.11.1"

lazy val lolhttp =
  (project in file("core")).
  settings(
    commonSettings,

    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "1.0.5",
      "org.typelevel" %% "cats-core" % "1.6.1",
      "org.typelevel" %% "cats-effect" % "1.3.1",
      "org.http4s" %% "blaze-http" % "0.14.8",
      "org.scalatest" %% "scalatest" % "3.0.4" % "test",
      "ch.qos.logback" % "logback-classic" % "1.2.5" % "test"
    ),

    assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
    publishArtifact in (Compile, packageBin) := false,
    artifact in (Compile, assembly) := {
      val core = (artifact in (Compile, packageBin)).value
      val vendorised = (artifact in (Compile, assembly)).value
      vendorised
    },
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  settings(addArtifact(artifact in (Compile, assembly), assembly): _*)

lazy val loljson =
  (project in file("json")).
  settings(
    commonSettings,

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val lolhtml =
  (project in file("html")).
  settings(
    commonSettings,

    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "fastparse" % "1.0.0",
      scalaOrganization.value % "scala-reflect" % scalaVersion.value
    ),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val examples: Project =
  project.
  configs(IntegrationTest).
  settings(
    commonSettings,
    Defaults.itSettings,

    publishArtifact := false,

    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core",
      "org.tpolecat" %% "doobie-h2"
    ).map(_ % "0.7.0"),

    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.5",

    libraryDependencies += "io.circe" %% "circe-optics" % "0.11.0",

    fork in IntegrationTest := true,

    // Running HTTP2 examples with Java 8 requires to install the right version of alpn-boot.
    // See http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-starting
    javaOptions in IntegrationTest := {
      Option(System.getProperty("java.version")).getOrElse("") match {
        case noAlpn if noAlpn.startsWith("1.7") || noAlpn.startsWith("1.8") =>
          if(file("alpn-boot.jar").exists)
            println(s"Picking alpn-boot.jar from ${file("alpn-boot.jar").getAbsolutePath}")
          Seq(s"""-Xbootclasspath/p:${file("alpn-boot.jar").getAbsolutePath}""")
        case _ =>
          Nil
      }
    },
    connectInput in IntegrationTest := true
  ).
  settings(
    Option(System.getProperty("generateExamples")).map(_ => Seq(
      autoCompilerPlugins := true,
      addCompilerPlugin("com.criteo.socco" %% "socco-plugin" % "0.1.9"),
      scalacOptions := Seq(
        "-P:socco:out:examples/target/html",
        "-P:socco:package_lol.html:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_lol.json:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_lol.http:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_scala.concurrent:http://www.scala-lang.org/api/current/",
        "-P:socco:package_io.circe:http://circe.github.io/circe/api/",
        "-P:socco:package_doobie:https://static.javadoc.io/org.tpolecat/doobie-core_2.12/0.6.0",
        "-P:socco:package_cats.effect:https://oss.sonatype.org/service/local/repositories/releases/archive/org/typelevel/cats-effect_2.12/1.0.0/cats-effect_2.12-1.0.0-javadoc.jar/!/",
        "-P:socco:package_fs2:https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/1.0.0/fs2-core_2.12-1.0.0-javadoc.jar/!/"
      )
    )).getOrElse(Nil): _*
  ).
  dependsOn(lolhttp % "compile->compile;test->test;it->test", loljson, lolhtml)

lazy val root =
  (project in file(".")).
  enablePlugins(ScalaUnidocPlugin).
  settings(
    commonSettings,

    publishArtifact := false,
    scalacOptions in (Compile,doc) ++= Seq(
      Seq(
        "-sourcepath", baseDirectory.value.getAbsolutePath
      ),
      Opts.doc.title("lolhttp"),
      Opts.doc.version(VERSION),
      Opts.doc.sourceUrl("https://github.com/criteo/lolhttp/blob/master€{FILE_PATH}.scala")
    ).flatten,
    // Not so useful for now because of SI-9967
    unidocAllAPIMappings in (ScalaUnidoc, unidoc) ++= {
      val allJars = {
        (fullClasspath in lolhttp in Compile).value ++
        (fullClasspath in loljson in Compile).value ++
        (fullClasspath in lolhtml in Compile).value
      }
      Seq(
        allJars.
          flatMap(x => x.metadata.get(moduleID.key).map(m => x.data -> m)).
          collect {
            case (jar, module) if module.name == "scala-library" =>
              jar -> "https://www.scala-lang.org/api/current/"
            case (jar, module) if module.name == "fs2-core_2.12" =>
              jar -> "https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/1.0.0-M3/fs2-core_2.12-1.0.0-M3-javadoc.jar/!/"
            case (jar, module) if module.name == "cats-effect_2.12" =>
              jar -> "https://oss.sonatype.org/service/local/repositories/releases/archive/org/typelevel/cats-effect_2.12/1.0.0-RC2/cats-effect_2.12-1.0.0-RC2-javadoc.jar/!/"
            case (jar, module) if module.name.startsWith("circe") =>
              jar -> "https://circe.github.io/circe/api/"
          }.
          toMap.
          mapValues(url => new java.net.URL(url))
      )
    },
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(lolhttp, loljson, lolhtml)
  ).
  aggregate(lolhttp, loljson, lolhtml, examples)
