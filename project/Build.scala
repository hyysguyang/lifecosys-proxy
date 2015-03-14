import sbt._
import Keys._


object ProxyBuild extends Build {

  import BuildSettings._
  import Dependencies._


  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("lifecosys-proxy", file("."))
    .aggregate(core, proxy)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)

  lazy val core = Project("core", file("core"))
    .settings(projectBuildSettings: _*)
    .settings(libraryDependencies ++=
    compile(netty, config, bouncycastle, jasypt, commonsLang, commonsIO, scalalogging,log4j, dnsjava intransitive()) ++
      test( scalatest, fluentHC)
    )


  lazy val proxy = Project("proxy", file("proxy"))
    .dependsOn(core % "compile->compile;test->test")
    .settings(projectBuildSettings: _*)

//  import sbtandroid.AndroidPlugin._
//  lazy val android = Project("Android", file("android"))
//    .dependsOn(core)
//    .settings(projectBuildSettings: _*)
//    .settings(libraryDependencies ++=  compile(slf4jAndroid))
//    .settings(androidSettings:_*)
//    .overrideConfigs((Configurations.default ++ Seq(Preload, Release)):_*)
//

}
