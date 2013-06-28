
import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import org.scalasbt.androidplugin._
import org.scalasbt.androidplugin.AndroidKeys._
import scala.Some


object BuildSettings {
  val VERSION = "1.0-alpha3"

  val basicSettings = Defaults.defaultSettings ++ seq(
    version := NightlyBuildSupport.buildVersion(VERSION),
    homepage := Some(new URL("https://lifecosys.com/developer/lifecosys-toolkit")),
    organization := "com.lifecosys",
    organizationHomepage := Some(new URL("https://lifecosys.com")),
    description := "Lifecosys toolkit system, include toolkit, aggregate different SNS service such as facebook, twitter, sina weibo etc.",
    startYear := Some(2013),
    scalaVersion := "2.10.1",
    resolvers ++= Dependencies.resolutionRepos,
//    logLevel := Level.Debug,
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

  lazy val moduleSettings =
    basicSettings ++ formatSettings ++
      NightlyBuildSupport.settings ++
      net.virtualvoid.sbt.graph.Plugin.graphSettings ++
      seq(
        // scaladoc settings
        (scalacOptions in doc) <++= (name, version).map {
          (n, v) => Seq("-doc-title", n, "-doc-version", v)
        },
        crossPaths := false,
        publishMavenStyle := true
      )

  val baseAndroidSettings = Seq(
    versionCode := 0,
    platformName in Android := "android-4.2",
    useProguard in Android := true
  )

  lazy val fullAndroidSettings = baseAndroidSettings ++
    AndroidProject.androidSettings ++
    TypedResources.settings ++
    AndroidManifestGenerator.settings ++
    AndroidMarketPublish.settings ++ Seq(
    keyalias in Android := "TODO:change-me"
  )


  lazy val noPublishing = seq(
    publish :=(),
    publishLocal :=()
  )


  lazy val siteSettings = basicSettings ++ formatSettings ++ noPublishing

  lazy val docsSettings = basicSettings ++ noPublishing ++ seq(
    unmanagedSourceDirectories in Test <<= baseDirectory {
      _ ** "code" get
    }
  )


  import com.earldouglas.xsbtwebplugin._
  import WebPlugin._
  import PluginKeys._

  val jettyPort=8080
  val jettySSLPort=8443
  def webContainer = config("container")

  lazy val jettySettings = basicSettings ++ noPublishing ++ webSettings ++ Seq(
    port in webContainer := jettyPort,
    ssl in webContainer := Some(8443, "/Develop/Project/home/lifecosys-toolkit/src/main/conf/keystore","killccp", "killccp"),
    scanInterval in Compile := 60
  )
  //  ++ disableJettyLogSettings
  //
  //  lazy val disableJettyLogSettings = inConfig(container.Configuration) {
  //    seq(
  //      start <<= (state, port, apps, customConfiguration, configurationFiles, configurationXml) map {
  //        (state, port, apps, cc, cf, cx) =>
  //          state.get(container.attribute).get.start(port, None, Utils.NopLogger, apps, cc, cf, cx)
  //      }
  //    )
  //  }

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences
  )

  import scalariform.formatter.preferences._

  def formattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, true)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)

}