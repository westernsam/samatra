name := "samatra"

organization := "com.springer"

version := Option(System.getenv("GO_PIPELINE_LABEL")).getOrElse("LOCAL")

crossScalaVersions := Seq("2.12.3", "2.11.7")

scalaVersion := crossScalaVersions.value.head

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-Xlint")

val jettyVersion = "9.3.6.v20151106"

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
    "org.eclipse.jetty" % "jetty-jmx" % jettyVersion % "test",

    "org.scalatest" %% "scalatest" % "3.0.0" % "test",
    "org.asynchttpclient" % "async-http-client" % "2.0.32" % "test"
  )

parallelExecution in Test := false
