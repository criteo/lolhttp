val VERSION = "0.2.0"

lazy val commonSettings = Seq(
  organization := "com.criteo.lolhttp",
  version := VERSION,
  scalaVersion := "2.12.1",
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

  // Maven config
  publishTo := Some("Criteo thirdparty" at "http://nexus.criteo.prod/content/repositories/criteo.thirdparty"),
  credentials += Credentials("Sonatype Nexus Repository Manager", "nexus.criteo.prod", System.getenv("MAVEN_USER"), System.getenv("MAVEN_PASSWORD")),

  // Useful to run flakey tests
  commands += Command.single("repeat") { (state, arg) =>
    arg :: s"repeat $arg" :: state
  },

  // Run example in another JVM, and quit on key press
  commands += Command.single("example") { (state, arg) =>
    s"examples/test:runMain TestExample $arg" :: state
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
      "co.fs2" %% "fs2-core" % "0.9.2",
      "io.netty" % "netty-codec-http2" % "4.1.9.Final",
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
      "io.circe" %% "circe-parser"
    ).map(_ % "0.6.0") ++ Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val lolhtml =
  (project in file("html")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),
    pomPostProcess := removeDependencies("org.scalatest")
  ).
  dependsOn(lolhttp % "compile->compile;test->test")

lazy val examples =
  project.
  settings(commonSettings: _*).
  settings(
    publishArtifact := false,
    fork in Test := true,
    connectInput in Test := true,
    scalacOptions := (if(file("/Users/g.bort/lol/socco/target/scala-2.12/socco-assembly-1.0.0.jar").exists)
      Seq(
        "-Xplugin:/Users/g.bort/lol/socco/target/scala-2.12/socco-assembly-1.0.0.jar",
        "-P:socco:out:examples/target/html",
        "-P:socco:package_lol.html:http://g.bort.gitlab.preprod.crto.in/lolhttp/api/",
        "-P:socco:package_lol.json:http://g.bort.gitlab.preprod.crto.in/lolhttp/api/",
        "-P:socco:package_lol.http:http://g.bort.gitlab.preprod.crto.in/lolhttp/api/",
        "-P:socco:package_scala.concurrent:http://www.scala-lang.org/api/current/",
        "-P:socco:package_fs2:https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/0.9.4/fs2-core_2.12-0.9.4-javadoc.jar/!/"
      )
    else Nil)
  ).
  dependsOn(lolhttp, loljson, lolhtml)

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
      Opts.doc.sourceUrl("https://gitlab.criteois.com/g.bort/lolhttp/tree/master€{FILE_PATH}.scala")
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
              jar -> "https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/0.9.4/fs2-core_2.12-0.9.4-javadoc.jar/!/"
          }.
          toMap.
          mapValues(url => new java.net.URL(url))
      )
    },
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(lolhttp, loljson, lolhtml)
  ).
  aggregate(lolhttp, loljson, lolhtml, examples)
