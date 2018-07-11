val jettyVersion = "9.4.10.v20180503"

libraryDependencies ++=
  Seq(
    "javax.websocket" % "javax.websocket-api" % "1.1",

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
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.asynchttpclient" % "async-http-client" % "2.4.7" % "test"
  )