lazy val akkaHttpVersion = "10.0.9"
lazy val akkaVersion = "2.5.3"
lazy val sprayVersion = "1.3.2"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.2"
    )),
    name := "akka-test",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.9",
      "com.typesafe.akka" %% "akka-persistence" % "2.5.3",

      "org.scalatest" %% "scalatest" % "3.0.1" % Test
    )
  )
