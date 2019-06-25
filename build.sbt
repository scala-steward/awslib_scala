/* Copyright 2012-2017 Micronautics Research Corporation. */

import sbt.Keys._

name := "awslib_scala"
organization := "com.micronautics"
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
scalaVersion := "2.13.0"
crossScalaVersions := Seq("2.10.6", "2.11.11", "2.12.8", "2.13.0")

scalacOptions ++=
  scalaVersion {
    case sv if sv.startsWith("2.10") => List(
      "-target:jvm-1.7"
    )

    case _ => List(
      "-target:jvm-1.8",
      "-Ywarn-unused"
    )
  }.value ++ Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-Xlint"
  )

fork in Test := false

// define the statements initially evaluated when entering 'console', 'console-quick', or 'console-project'
initialCommands := """
                     |""".stripMargin

javacOptions ++=
  scalaVersion {
    case sv if sv.startsWith("2.10") => List(
      "-source", "1.7",
      "-target", "1.7"
    )

    case _ => List(
      "-source", "1.8",
      "-target", "1.8"
    )
  }.value ++ Seq(
    "-Xlint:deprecation",
    "-Xlint:unchecked",
    "-g:vars"
  )

scalacOptions in (Compile, doc) ++= baseDirectory.map {
  bd: File => Seq[String](
     "-sourcepath", bd.getAbsolutePath,
     "-doc-source-url", "https://github.com/mslinn/{name.value}/tree/master€{FILE_PATH}.scala"
  )
}.value

resolvers ++= Seq(
  "Typesafe Releases"             at "http://repo.typesafe.com/typesafe/releases",
  "micronautics/scala on bintray" at "http://dl.bintray.com/micronautics/scala"
)

libraryDependencies ++= {
  val httpV = "4.4.1"
  val jackV = "2.9.9"
  Seq(
    "org.apache.httpcomponents"  %  "httpclient"          % httpV      withSources() force(),
    "org.apache.httpcomponents"  %  "httpcore"            % httpV      withSources() force(),
    "org.apache.httpcomponents"  %  "httpmime"            % httpV      withSources() force(),
    "org.codehaus.jackson"       %  "jackson-mapper-asl"  % "1.9.13"   withSources(),
    "org.joda"                   %  "joda-convert"        % "1.7"      withSources() force(),
    "com.amazonaws"              %  "aws-java-sdk-osgi"   % "1.11.327" withSources(),
    "com.fasterxml.jackson.core" %  "jackson-annotations" % jackV      withSources() force(),
    "com.fasterxml.jackson.core" %  "jackson-core"        % jackV      withSources() force(),
    "com.fasterxml.jackson.core" %  "jackson-databind"    % jackV      withSources(),
    "com.google.code.findbugs"   %  "jsr305"              % "3.0.1"    withSources() force(),
    "com.micronautics"           %% "scalacourses-utils"  % "0.3.0"    withSources(),
    "com.typesafe"               %  "config"              % "1.3.0"    withSources() force(),
    "commons-codec"              %  "commons-codec"       % "1.10"     withSources() force(),
    "commons-io"                 %  "commons-io"          % "2.4"      withSources(),
    "commons-lang"               %  "commons-lang"        % "2.6"      withSources(),
    "ch.qos.logback"             %  "logback-classic"     % "1.1.3"    withSources(),
    "org.slf4j"                  %  "slf4j-api"           % "1.7.12"   withSources() force(),
    //
    "junit"                      %  "junit"               % "4.12"  % Test
  )
}

libraryDependencies ++= scalaVersion {
  case sv if sv.startsWith("2.13") => // Builds with Scala 2.13.x, Play 2.6.x
    Seq(
      "com.typesafe.play"        %% "play-json"        % "2.7.4" withSources() force(),
      "org.clapper"              %% "grizzled-scala"   % "4.9.3" withSources(),
      //
      "com.typesafe.play"        %% "play"             % "2.7.3" % Test withSources(),
      "com.typesafe.play"        %% "play-ws"          % "2.7.3" % Test withSources(),
      "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3" % Test withSources()
    )

  case sv if sv.startsWith("2.12") => // Builds with Scala 2.12.x, Play 2.6.x
    val playV = "2.6.0-M1"
    Seq(
      "com.typesafe.play"        %% "play-json"        % playV   withSources() force(),
      "org.clapper"              %% "grizzled-scala"   % "4.2.0" withSources(),
      //
      "com.typesafe.play"        %% "play"             % playV   % Test withSources(),
      "com.typesafe.play"        %% "play-ws"          % playV   % Test withSources(),
      "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3" % Test withSources()
    )

  case sv if sv.startsWith("2.11") => // Builds with Scala 2.11.x, Play 2.5.x
    val playV = "2.5.12"
    Seq(
      "com.typesafe.play"        %% "play-json"        % playV    withSources() force(),
      "com.typesafe.play"        %% "play-iteratees"   % playV    withSources() force(),
      "com.typesafe.play"        %% "play-datacommons" % playV    withSources() force(),
      "com.github.nscala-time"   %% "nscala-time"      % "2.16.0" withSources(),
      "org.clapper"              %% "grizzled-scala"   % "4.2.0"  withSources(),
      //
      "com.typesafe.play"      %% "play"               % playV    % Test withSources(),
      "com.typesafe.play"      %% "play-ws"            % playV    % Test withSources(),
      "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3"  % Test withSources()
    )

  case sv if sv.startsWith("2.10") => // Builds with Scala 2.10.x, Play 2.2.6
    val playV = "2.2.6"
    Seq(
      "com.typesafe.play"        %% "play-json"           % playV    withSources() force(),
      "com.typesafe.play"        %% "play-iteratees"      % playV    withSources() force(),
      "com.typesafe.play"        %% "play-datacommons"    % playV    withSources() force(),
      "com.github.nscala-time"   %% "nscala-time"         % "2.4.0"  exclude("joda-time", "joda-time"),
      "joda-time"                %  "joda-time"           % "2.8.2",
      "org.clapper"              %  "grizzled-scala_2.10" % "1.3"    withSources(),
      "org.scala-lang"           %  "scala-reflect"       % "2.10.6" force(),
      //
      "com.typesafe.play"        %% "play"                % playV      % Test withSources(),
      "org.scalatestplus"        %% "play"                % "1.4.0-M4" % Test withSources()
    )
}.value

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

//updateOptions := updateOptions.value.withCachedResolution(cachedResoluton = true)
logBuffered in Test := false

//logLevel := Level.Error

// Only show warnings and errors on the screen for compilations.
// This applies to both test:compile and compile and is Info by default
//logLevel in compile := Level.Warn
logLevel in test := Level.Info // Level.INFO is needed to see detailed output when running tests

name := "awslib_scala"

organization := "com.micronautics"

parallelExecution in Test := false

resolvers ++= Seq(
  "Typesafe Releases"             at "http://repo.typesafe.com/typesafe/releases",
  "micronautics/scala on bintray" at "http://dl.bintray.com/micronautics/scala"
)

scalacOptions ++=
  scalaVersion {
    case sv if sv.startsWith("2.10") => List(
      "-target:jvm-1.7"
    )

    case _ => List(
      "-target:jvm-1.8",
      "-Ywarn-unused"
    )
  }.value ++ Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-Ywarn-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture",
    "-Xlint"
  )

scalacOptions in (Compile, doc) ++= baseDirectory.map {
  bd: File => Seq[String](
     "-sourcepath", bd.getAbsolutePath,
     "-doc-source-url", "https://github.com/mslinn/awslib_scala/tree/master€{FILE_PATH}.scala"
  )
}.value

scalaVersion := "2.12.8"

scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/mslinn/$name"),
    s"git@github.com:mslinn/$name.git"
  )
)

version := "1.1.13"
