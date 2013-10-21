import sbt._
import Keys._
import com.lifecosys.sbt.DistPlugin._


object LifecosysToolkitBuild extends Build {

  import BuildSettings._
  import Dependencies._


  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("lifecosys-toolkit", file("."))
    .aggregate(core, proxy, android)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)


  lazy val core = Project("Core", file("core"))
    .settings(projectBuildSettings: _*)
    .settings(libraryDependencies ++=
    compile(netty, config, bouncycastle, jasypt, commonsLang, commonsIO,parboiled, scalalogging, dnsjava intransitive()) ++
//      compile(spray: _*) ++
      compile(slf4jApi) ++
      test(junit, scalatest, fluentHC)
  )

  private def includeFiles = (baseDirectory, baseDirectory) map {
    (b, c) ⇒ com.lifecosys.sbt.DistPlugin.includeAdditionalFiles(b.getParentFile)
  }
  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(projectBuildSettings: _*)
    .settings(distSettings ++ Seq(additionalFiles in com.lifecosys.sbt.DistPlugin.Dist <<= includeFiles): _*)
    //.settings((AkkaKernelPlugin.distSettings ++ Seq(distMainClass in Dist := "com.lifecosys.toolkit.proxy.ProxyServerLauncher" )):_*)
    .settings(libraryDependencies ++=  runtime(slf4jLog4j12,log4j) ++ test(junit, scalatest, fluentHC))

  import sbtandroid.AndroidPlugin._
  lazy val android = Project("Android", file("android"))
    .dependsOn(core)
    .settings(projectBuildSettings: _*)
    .settings(libraryDependencies ++=  compile(slf4jAndroid))
    .settings(androidSettings:_*)
    .overrideConfigs((Configurations.default ++ Seq(Preload, Release)):_*)


}
