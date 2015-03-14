import com.typesafe.sbt.SbtScalariform.{ScalariformKeys, _}
import sbt.Keys._
import sbt._

/**
 * @author Young Gu
 */
object BuildSettings {
  val VERSION = "1.0-beta2-SNAPSHOT"

  val basicSettings = Defaults.coreDefaultSettings ++ Seq(
    version := VERSION,
    homepage := Some(new URL("https://lifecosys.com/developer/lifecosys-prxoy")),
    organization := "com.lifecosys.proxy",
    organizationHomepage := Some(new URL("https://lifecosys.com")),
    description := "Lifecosys Proxy.",
    startYear := Some(2015),
    scalaVersion := "2.11.4",
    scalacOptions := Seq(
      "-encoding", "utf8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-target:jvm-1.6",
      "-language:postfixOps",
      "-language:implicitConversions",
      "-Xlog-reflective-calls"
    )
  )

  import net.virtualvoid.sbt.graph.Plugin._

  lazy val projectBuildSettings = basicSettings ++ formattingSettings ++ graphSettings ++
    Seq(
      publishMavenStyle := true,
      fork in Test := true
    )

  lazy val itSettings = Defaults.itSettings ++ Seq(
    fork in IntegrationTest := true,
    javaOptions in(IntegrationTest, run) += "-Xms256m -Xmx512m -XX:ReservedCodeCacheSize=128m -XX:MaxMetaspaceSize=256m"
  )


  lazy val noPublishing = seq(
    publish :=(),
    publishLocal :=()
  )

  lazy val siteSettings = basicSettings ++ formattingSettings ++ noPublishing


  import scalariform.formatter.preferences._

  val formattingSettings = scalariformSettings ++ Seq(
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(RewriteArrowSymbols, true)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(PreserveDanglingCloseParenthesis, true))


}
