organization := "com.jamesward"

name := "zio-bedrock-converse"

scalaVersion := "3.9.0"

scalacOptions ++= Seq(
  // "-Yexplicit-nulls", // not sure where it went
  "-language:strictEquality",
  "-deprecation",
  "-release", "17",
  // "-Xfatal-warnings", // not sure where it went
)

val zioVersion = "2.1.26"

val zioSchemaVersion = "1.9.0"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                    % zioVersion,
  "dev.zio" %% "zio-direct"             % "1.0.0-RC7",
  "dev.zio" %% "zio-http"               % "3.11.6",
  "dev.zio" %% "zio-schema-derivation"  % zioSchemaVersion,
  "dev.zio" %% "zio-schema-json"        % zioSchemaVersion,

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
)

fork := true

javaOptions ++= Seq(
  "-Djava.net.preferIPv4Stack=true",
  // JDK 25: suppress sun.misc.Unsafe / restricted-method warnings
  // emitted by upstream libs (scala-library, netty-common).
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)

licenses := Seq("Apache 2.0" -> uri("https://opensource.org/licenses/apache-2.0"))

homepage := Some(uri("https://github.com/jamesward/zio-bedrock-converse"))

developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    uri("https://jamesward.com")
  )
)

ThisBuild / versionScheme := Some("semver-spec")
