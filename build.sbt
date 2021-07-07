
// Our Scala versions.
lazy val `scala-2.13` = "2.13.5"
lazy val `scala-3`    = "3.0.0"

// Publishing
name         := "pool-party"
organization := "org.tpolecat"
licenses    ++= Seq(("MIT", url("http://opensource.org/licenses/MIT")))
homepage     := Some(url("https://github.com/tpolecat/pool-party"))
developers   := List(
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
scalaVersion       := `scala-2.13`
crossScalaVersions := Seq(`scala-2.13`, `scala-3`)
Compile / doc     / scalacOptions --= Seq("-Xfatal-warnings")
Compile / doc     / scalacOptions ++= Seq(
  "-groups",
  "-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath,
  "-doc-source-url", "https://github.com/tpolecat/pool-party/blob/v" + version.value + "€{FILE_PATH}.scala",
)
libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect"         % "3.1.1",
  "org.typelevel" %% "munit-cats-effect-3" % "1.0.5" % "test"
)

// MUnit
libraryDependencies += "org.scalameta" %% "munit" % "0.7.26" % Test
testFrameworks += new TestFramework("munit.Framework")

// dottydoc really doesn't work at all right now
Compile / doc / sources := {
  val old = (Compile / doc / sources).value
  if (scalaVersion.value.startsWith("3."))
    Seq()
  else
    old
}

enablePlugins(AutomateHeaderPlugin)
