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
    .aggregate(core, proxy,proxyHeroku,proxyWeb,android)
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

 // import akka.sbt.AkkaKernelPlugin
 // import akka.sbt.AkkaKernelPlugin._
  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    //.settings((AkkaKernelPlugin.distSettings ++ Seq(distMainClass in Dist := "com.lifecosys.toolkit.proxy.ProxyServerLauncher" )):_*)
    .settings(libraryDependencies ++=test(junit, scalatest))


  import com.typesafe.startscript.StartScriptPlugin
  lazy val proxyHeroku = Project("ProxyHeroku", file("proxy-heroku"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(StartScriptPlugin.startScriptForClassesSettings: _*)
    .settings(libraryDependencies ++=test(junit, scalatest))
    .settings( mainClass in Compile := Some("com.lifecosys.toolkit.proxy.ProxyServerLauncher"))


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
