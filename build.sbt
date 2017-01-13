
lazy val lolhttp =
  (project in file(".")).
  settings(
    organization := "lolstack.lol",
    version := "0.1.0",
    scalaVersion := "2.11.8",
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
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "0.9.2",
      "io.undertow" % "undertow-core" % "1.4.8.Final",
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.55",
      "org.bouncycastle" % "bcprov-jdk15on" % "1.55",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),
    fork in Test := true
  )
