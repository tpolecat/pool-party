
// Our Scala versions.
lazy val `scala-2.13` = "2.13.6"
lazy val `scala-3`    = "3.0.2"

// Publishing
ThisBuild / name         := "pool-party"
ThisBuild / organization := "org.tpolecat"
ThisBuild / licenses    ++= Seq(("MIT", url("http://opensource.org/licenses/MIT")))
ThisBuild / homepage     := Some(url("https://github.com/tpolecat/pool-party"))
ThisBuild / developers   := List(
  Developer("tpolecat", "Rob Norris", "rob_norris@mac.com", url("http://www.tpolecat.org"))
)

// Headers
headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment)
headerLicense  := Some(HeaderLicense.Custom(
  """|Copyright (c) 2021 by Rob Norris
     |This software is licensed under the MIT License (MIT).
     |For more information see LICENSE or https://opensource.org/licenses/MIT
     |""".stripMargin
  )
)

// Compilation
ThisBuild / scalaVersion       := `scala-2.13`
ThisBuild / crossScalaVersions := Seq(`scala-2.13`, `scala-3`)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .settings(
    Compile / unmanagedSourceDirectories := Seq.empty,
    Test / unmanagedSourceDirectories := Seq.empty,
  )
  .aggregate(poolparty.jvm, poolparty.js)


lazy val poolparty = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "pool-party",

    // Headers
    headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
    headerLicense  := Some(HeaderLicense.Custom(
      """|Copyright (c) 2021 by Rob Norris
        |This software is licensed under the MIT License (MIT).
        |For more information see LICENSE or https://opensource.org/licenses/MIT
        |""".stripMargin
      )
    ),

    // Compilation
    Compile / doc / scalacOptions --= Seq("-Xfatal-warnings"),
    Compile / doc / scalacOptions ++= Seq(
      "-groups",
      "-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath,
      "-doc-source-url", "https://github.com/tpolecat/pool-party/blob/v" + version.value + "€{FILE_PATH}.scala",
    ),

    // MUnit
    libraryDependencies ++= Seq(
      "org.typelevel"     %%% "cats-effect"         % "3.3.0",
      "io.github.cquiroz" %%% "scala-java-time"     % "2.3.0",
      "org.typelevel"     %%% "munit-cats-effect-3" % "1.0.6" % "test",
    ),
    testFrameworks += new TestFramework("munit.Framework"),

    // Scala 2 needs scala-reflect
    libraryDependencies ++= Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value).filterNot(_ => scalaVersion.value.startsWith("3.")),

    // dottydoc really doesn't work at all right now
    Compile / doc / sources := {
      val old = (Compile / doc / sources).value
      if (scalaVersion.value.startsWith("3."))
        Seq()
      else
        old
    }
  )
