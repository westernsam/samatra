val jettyVersion = "9.4.46.v20220331"

libraryDependencies ++=
  Seq(
    "javax.websocket" % "javax.websocket-api" % "1.1",
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.7.0",

    "org.eclipse.jetty" % "jetty-webapp" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-server" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-http" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-io" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-security" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-servlets" % jettyVersion % "test",
    "org.eclipse.jetty" % "jetty-util" % jettyVersion % "test",
    "org.eclipse.jetty.websocket" % "websocket-api" % jettyVersion % "test",
    "org.eclipse.jetty.websocket" % "javax-websocket-server-impl" % jettyVersion % "test",
    "org.scalatest" %% "scalatest" % "3.2.11" % "test",
    "org.asynchttpclient" % "async-http-client" % "2.12.3" % "test"
  )