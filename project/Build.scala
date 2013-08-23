import com.lifecosys.sbt.DistPlugin._
import java.io.File
import sbt._
import Keys._


object LifecosysToolkitBuild extends Build {

  import BuildSettings._
  import Dependencies._

  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("lifecosys-toolkit", file("."))
    .aggregate(core, proxy, proxyWeb, android)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)


  lazy val core = Project("Core", file("core"))
    .settings(moduleSettings: _*)
    .settings(libraryDependencies ++=
    compile(netty, config, bouncycastle, jasypt, commonsLang, commonsIO,parboiled, akkaActor, scalalogging, dnsjava intransitive()) ++
//      compile(spray: _*) ++
      compile(slf4jLog4j12,log4j) ++
      test(junit, scalatest, fluentHC)
  )

  private def includeFiles = (baseDirectory, baseDirectory) map {
    (b, c) ⇒ com.lifecosys.sbt.DistPlugin.includeAdditionalFiles(b.getParentFile)
  }
  lazy val proxy = Project("Proxy", file("proxy"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(distSettings ++ Seq(additionalFiles in com.lifecosys.sbt.DistPlugin.Dist <<= includeFiles): _*)
    //.settings((AkkaKernelPlugin.distSettings ++ Seq(distMainClass in Dist := "com.lifecosys.toolkit.proxy.ProxyServerLauncher" )):_*)
    .settings(libraryDependencies ++= runtime() ++ test(junit, scalatest, fluentHC))

  lazy val proxyWeb = Project("ProxyWeb", file("proxy-web"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(jettySettings: _*)
    .settings(libraryDependencies ++=
//    test(sprayTestkit) ++
      compile(servlet30) ++
      container(jettyWebApp)
  )

  lazy val android = Project("Android", file("android"))
    .dependsOn(core)
    .settings(moduleSettings: _*)
    .settings(fullAndroidSettings: _*)


}
