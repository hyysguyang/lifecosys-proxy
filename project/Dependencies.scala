import sbt._

object Dependencies {

  val resolutionRepos = Seq(
    "Local Maven Repository" at "file:////Develop/MavenRepository",
    "spray repo" at "http://repo.spray.io/",
    Opts.resolver.sonatypeReleases,
    Opts.resolver.sonatypeSnapshots
  )

  def compile(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "compile")

  def provided(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "provided")

  def test(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "test")

  def runtime(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "runtime")

  def container(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "container")

  val netty = "io.netty" % "netty" % "3.6.5.Final"  artifacts(Artifact("netty", "jar", "jar"))
  val config = "com.typesafe" % "config" % "1.0.0"  artifacts(Artifact("config", "jar", "jar"))
  val bouncycastle = "org.bouncycastle" % "bcprov-jdk16" % "1.46"
  val jasypt = "org.jasypt" % "jasypt" % "1.9.0"
  val commonsLang = "org.apache.commons" % "commons-lang3" % "3.1"
  val commonsIO = "commons-io" % "commons-io" % "2.4"
  val scalalogging = "com.typesafe" %% "scalalogging-slf4j" % "1.1.0-SNAPSHOT"
  val slf4jLog4j12 = "org.slf4j" % "slf4j-log4j12" % "1.7.5"
  val log4j = "log4j" % "log4j" % "1.2.17"
  val fluentHC = "org.apache.httpcomponents" % "fluent-hc" % "4.3-beta2"
  val dnsjava = "dnsjava" % "dnsjava" % "2.1.1"

  val junit = "junit" % "junit" % "4.8.2"

  val sprayVersion = "1.1-SNAPSHOT"
  val sprayRouting = "io.spray" % "spray-routing" % sprayVersion
  val sprayCan = "io.spray" % "spray-can" % sprayVersion
  val sprayCaching = "io.spray" % "spray-caching" % sprayVersion
  val sprayServlet = "io.spray" % "spray-servlet" % sprayVersion
  val sprayTestkit = "io.spray" % "spray-testkit" % sprayVersion
  val spray = Seq(sprayCan, sprayRouting, sprayCaching, sprayServlet)


  val scalaReflect = "org.scala-lang" % "scala-reflect" % "2.10.1"
  val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.1.2"
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % "2.1.2"
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.5"
  val shapeless = "com.chuusai" %% "shapeless" % "1.2.4"
  val scalatest = "org.scalatest" %% "scalatest" % "1.9.1"
  val sprayJson = "io.spray" %% "spray-json" % "1.2.3"
  val jettyWebApp = "org.eclipse.jetty" % "jetty-webapp" % "8.1.10.v20130312"
  val servlet30 = "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016"
  val logback = "ch.qos.logback" % "logback-classic" % "1.0.12"
  val pegdown = "org.pegdown" % "pegdown" % "1.2.1"
}
