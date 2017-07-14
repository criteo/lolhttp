val VERSION = "0.5.1"

lazy val commonSettings = Seq(
  organization := "com.criteo.lolhttp",
  version := VERSION,
  scalaVersion := "2.12.2",
  crossScalaVersions := Seq("2.11.11", "2.12.2"),
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

  // Tests
  fork in Test := Option(System.getProperty("fork")).isDefined,
  testOptions in Test += Tests.Argument("-l", "Unsafe"),

  // Useful to run flakey tests
  commands += Command.single("repeat") { (state, arg) =>
    arg :: s"repeat $arg" :: state
  },

  // Run example in another JVM, and quit on key press
  commands += Command.single("example") { (state, arg) =>
    s"examples/test:runMain lol.http.examples.ExamplesTests $arg" :: state
  },

  // Run unsafe examples tests
  commands += Command.command("testExamples") { (state) =>
    val extracted = Project.extract(state)
    import extracted._
    val Some(testClassPath) = classDirectory in (examples, Test) get structure.data
    s"""examples/test:runMain org.scalatest.tools.Runner -R $testClassPath -o -s lol.http.examples.ExamplesTests""" ::
    state
  },

  // Run stress tests
  commands += Command.command("stressTests") { (state) =>
    val extracted = Project.extract(state)
    import extracted._
    val Some(testClassPath) = classDirectory in (examples, Test) get structure.data
    s"""examples/test:runMain org.scalatest.tools.Runner -R $testClassPath -o -s lol.http.examples.StressTests""" ::
    state
  },

  // Maven config
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    "criteo-oss",
    sys.env.getOrElse("SONATYPE_PASSWORD", "")
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

lazy val lolhttp =
  (project in file("core")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "0.10.0-M3",
      "io.netty" % "netty-codec-http2" % "4.1.11.Final",
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
    },
    pomPostProcess := removeDependencies("io.netty", "org.bouncycastle", "org.scalatest")
  ).
  settings(addArtifact(artifact in (Compile, assembly), assembly): _*)

lazy val loljson =
  (project in file("json")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
      "io.circe" %% "circe-optics"
    ).map(_ % "0.7.1") ++ Seq(
      "org.scalatest" %% "scalatest" % "3.0.3" % "test"
    ),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val lolhtml =
  (project in file("html")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.3" % "test"
    ),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val examples: Project =
  project.
  settings(commonSettings: _*).
  settings(
    publishArtifact := false,
    fork in Test := true,
    connectInput in Test := true
  ).
  settings(
    Option(System.getProperty("generateExamples")).map(_ => Seq(
      autoCompilerPlugins := true,
      addCompilerPlugin("com.criteo.socco" %% "socco-plugin" % "0.1.6"),
      scalacOptions := Seq(
        "-P:socco:out:examples/target/html",
        "-P:socco:package_lol.html:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_lol.json:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_lol.http:https://criteo.github.io/lolhttp/api/",
        "-P:socco:package_scala.concurrent:http://www.scala-lang.org/api/current/",
        "-P:socco:package_io.circe:http://circe.github.io/circe/api/",
        "-P:socco:package_fs2:https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/0.9.4/fs2-core_2.12-0.9.4-javadoc.jar/!/"
      )
    )).getOrElse(Nil): _*
  ).
  dependsOn(lolhttp % "compile->compile;test->test", loljson, lolhtml)

lazy val root =
  (project in file(".")).
  settings(commonSettings: _*).
  enablePlugins(ScalaUnidocPlugin).
  settings(
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
            case (jar, ModuleID("org.scala-lang", "scala-library", _, _, _, _, _, _, _, _, _)) =>
              jar -> "https://www.scala-lang.org/api/current/"
            case (jar, ModuleID("co.fs2", "fs2-core_2.12", _, _, _, _, _, _, _, _, _)) =>
              jar -> "https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/0.9.5/fs2-core_2.12-0.9.5-javadoc.jar/!/"
          }.
          toMap.
          mapValues(url => new java.net.URL(url))
      )
    },
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(lolhttp, loljson, lolhtml)
  ).
  aggregate(lolhttp, loljson, lolhtml, examples)
