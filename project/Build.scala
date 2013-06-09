import sbt._
import Keys._


object LifecosysToolkitBuild extends Build {

  import BuildSettings._
  import Dependencies._

  // configure prompt to show current project
  override lazy val settings = super.settings :+ {
    shellPrompt := {
      s => Project.extract(s).currentProject.id + " > "
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("lifecosys-toolkit", file("."))
    .aggregate(core, proxy,bibleServer,android)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)


  lazy val core = Project("Core", file("core"))
    .settings(moduleSettings: _*)
    .settings(libraryDependencies ++=
    compile(littleproxy,netty, config, bouncycastle, jasypt, commonsIO, slf4jAPI,akkaActor) ++
      compile(spray: _*) ++
      runtime(slf4jLog4j12) ++
      test(junit, scalatest, fluentHC)
  )

  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(moduleSettings: _*)

  lazy val bibleServer = Project("BibleServer", file("bible-server"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(jettySettings: _*)
    .settings(libraryDependencies ++=
      test(sprayTestkit) ++
      container(jettyWebApp, servlet30)
  )

  lazy val android = Project("Android", file("android"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(fullAndroidSettings: _*)


}