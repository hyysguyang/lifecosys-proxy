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
    .aggregate(core, proxy,proxyWeb,android)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)


  lazy val core = Project("Core", file("core"))
    .settings(moduleSettings: _*)
    .settings(libraryDependencies ++=
    compile(netty, config, bouncycastle, jasypt, commonsIO,akkaActor,scalalogging) ++
      compile(spray: _*) ++
      compile(littleproxy) ++
      compile(fluentHC) ++
      runtime(slf4jLog4j12) ++
      test(junit, scalatest)
  )

  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(moduleSettings: _*)

  lazy val proxyWeb = Project("ProxyWeb", file("proxy-web"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(jettySettings: _*)
    .settings(libraryDependencies ++=
      test(sprayTestkit) ++
      compile(servlet30) ++
      container(jettyWebApp)
  )

  lazy val android = Project("Android", file("android"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(fullAndroidSettings: _*)


}