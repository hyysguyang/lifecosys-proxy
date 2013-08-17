import sbt._
import Keys._


object LifecosysToolkitBuild extends Build {

  import BuildSettings._
  import Dependencies._

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
    compile(netty, config, bouncycastle, jasypt, commonsIO,akkaActor,scalalogging,dnssec4j) ++
      compile(spray: _*) ++
      runtime(slf4jLog4j12) ++
      test(junit, scalatest,fluentHC)
  )

 // import akka.sbt.AkkaKernelPlugin
 // import akka.sbt.AkkaKernelPlugin._
  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(distSettings: _*)
    //.settings((AkkaKernelPlugin.distSettings ++ Seq(distMainClass in Dist := "com.lifecosys.toolkit.proxy.ProxyServerLauncher" )):_*)
    .settings(libraryDependencies ++=test(junit, scalatest,fluentHC))

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
