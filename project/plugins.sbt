resolvers += "spray repo" at "http://repo.spray.io"

resolvers += "sbt-plugin-snapshots" at "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots"

resolvers += Resolver.url("scalasbt releases", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns)

resolvers += "typesafeOnArtifactoryonline" at "http://typesafe.artifactoryonline.com/typesafe/repo/"

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"


//please use the branch --android support-- of fxthomas' fork and change the version to 1.5.0-android-support.
//https://github.com/fxthomas/sbt-idea

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.0-android-support")

addSbtPlugin("org.scala-sbt" % "sbt-android" % "0.7.1-SNAPSHOT")

addSbtPlugin("com.lifecosys" % "sbt-dist-plugin" % "1.0.0-SNAPSHOT")


addSbtPlugin("com.earldouglas" % "xsbt-web-plugin" % "0.3.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.0.1")



