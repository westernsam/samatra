import sbt.{Path, Resolver}

name := "samatra"

lazy val commonSettings = Seq(
  organization := "com.springer",
  version := Option(System.getenv("GO_PIPELINE_LABEL")).getOrElse("LOCAL"),
  crossScalaVersions := Seq("2.12.3", "2.11.7"),
  scalaVersion := crossScalaVersions.value.head,
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-Xlint"),
  parallelExecution in Test := false,
  publishMavenStyle := true,
  publishTo := {
    Some(Resolver.file("Local Maven Repository", new File(Path.userHome.absolutePath + "~/.m2/repository")))
  }
)

val jettyVersion = "9.4.10.v20180503"

libraryDependencies ++=
  Seq(
    "javax.servlet" % "javax.servlet-api" % "3.1.0",

    "org.eclipse.jetty" % "jetty-webapp" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-server" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-http" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-io" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-security" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-servlets" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-util" % jettyVersion % "test",

    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.asynchtt2.4.7pclient" % "async-http-client" % "" % "test"
  )

lazy val `samatra-websockets` = project.in(file("samatra-websockets"))
  .settings(commonSettings: _*)

val `samatra`: sbt.Project = project.in(file("."))
  .settings(commonSettings: _*)
  .aggregate(`samatra-websockets`)
