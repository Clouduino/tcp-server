name := "clouduino-tcp-server"

scalaVersion := "2.11.6"

organization := "io.cloudiuno"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2",
  "com.typesafe.akka" %% "akka-actor" % "2.3.10"
)

testDependencies

def testDependencies = Seq(
  libraryDependencies ++= Seq(
    "org.qirx" %% "little-spec" % "0.4" % "test",
    "org.qirx" %% "little-spec-extra-documentation" % "0.4" % "test"
  ),
  testFrameworks += new TestFramework("org.qirx.littlespec.sbt.TestFramework"),
  testOptions += Tests.Argument(
    "reporter", "org.qirx.littlespec.reporter.MarkdownReporter"
  ),
  testOptions += Tests.Argument(
    "documentationTarget", (baseDirectory.value / "documentation").getAbsolutePath
  )
)
