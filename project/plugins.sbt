resolvers += "spray repo" at "http://repo.spray.io"

resolvers += Resolver.url("scalasbt releases", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns)

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"

resolvers += "twitter repo" at "http://maven.twttr.com/"

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.0-SNAPSHOT")

addSbtPlugin("org.scala-sbt" % "sbt-android-plugin" % "0.6.3-20130429-SNAPSHOT")


addSbtPlugin("com.earldouglas" % "xsbt-web-plugin" % "0.3.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.0.1")

addSbtPlugin("com.typesafe.startscript" % "xsbt-start-script-plugin" % "0.5.3")

//addSbtPlugin("com.lifecosys" % "sbt-dist-plugin" % "1.0.0")


