resolvers += "spray repo" at "http://repo.spray.io"

resolvers += "sbt-plugin-snapshots" at "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots"

resolvers += "typesafeOnArtifactoryonline" at "http://typesafe.artifactoryonline.com/typesafe/repo/"

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"


addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.0-SNAPSHOT")

addSbtPlugin("org.scala-sbt" % "sbt-android-plugin" % "0.6.3-20130429-SNAPSHOT")

addSbtPlugin("com.twitter" % "sbt-package-dist" % "1.1.1")


addSbtPlugin("com.earldouglas" % "xsbt-web-plugin" % "0.3.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.0.1")



