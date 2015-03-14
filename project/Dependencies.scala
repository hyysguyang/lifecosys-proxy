import sbt._

object Dependencies {

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
  val scalalogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"

  val log4j = "org.slf4j" % "slf4j-log4j12" % "1.7.8"


//  val slf4jAndroid =  "org.slf4j" % "slf4j-android" % slf4jVersion

  val fluentHC = "org.apache.httpcomponents" % "fluent-hc" % "4.3-beta2"
  val dnsjava = "dnsjava" % "dnsjava" % "2.1.1"

  val junit = "junit" % "junit" % "4.8.2"

  val scalaReflect = "org.scala-lang" % "scala-reflect" % "2.10.1"
  val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.1.2"
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % "2.1.2"
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.5"
  val shapeless = "com.chuusai" %% "shapeless" % "1.2.4"
  val scalatest = "org.scalatest" %% "scalatest" % "2.2.4"
  val sprayJson = "io.spray" %% "spray-json" % "1.2.3"
  val jettyWebApp = "org.eclipse.jetty" % "jetty-webapp" % "8.1.10.v20130312"
  val servlet30 = "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016"
  val logback = "ch.qos.logback" % "logback-classic" % "1.0.12"
  val pegdown = "org.pegdown" % "pegdown" % "1.2.1"
}
