import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import spray.revolver.RevolverPlugin._
import spray.revolver.Actions
import com.typesafe.sbt.SbtScalariform._
import org.scalastyle.sbt.ScalastylePlugin
import scalariform.formatter.preferences._

// There are advantages to using real Scala build files with SBT:
//  - Multi-JVM testing won't work without it, for now
//  - You get full IDE support
object JobServerBuild extends Build {
  lazy val dirSettings = Seq(
    unmanagedSourceDirectories in Compile <<= Seq(baseDirectory(_ / "src" )).join,
    unmanagedSourceDirectories in Test <<= Seq(baseDirectory(_ / "test" )).join,
    scalaSource in Compile <<= baseDirectory(_ / "src" ),
    scalaSource in Test <<= baseDirectory(_ / "test" )
  )

  import Dependencies._

  lazy val akkaApp = Project(id = "akka-app", base = file("akka-app"),
    settings = commonSettings210 ++ Seq(
      libraryDependencies ++= coreTestDeps ++ akkaDeps
    )
  )

  lazy val jobServer = Project(id = "job-server", base = file("job-server"),
    settings = commonSettings210 ++ Assembly.settings ++ Revolver.settings ++ Seq(
      libraryDependencies ++= sparkDeps ++ coreTestDeps,

      // Automatically package the test jar when we run tests here
      // And always do a clean before package (package depends on clean) to clear out multiple versions
      test in Test <<= (test in Test).dependsOn(packageBin in Compile in jobServerTestJar)
                                     .dependsOn(clean in Compile in jobServerTestJar),

      fullClasspath in Compile ++= extraJarPaths,
      javaOptions in Revolver.reStart += jobServerLogging,
      // Give job server a bit more PermGen since it does classloading
      javaOptions in Revolver.reStart += "-XX:MaxPermSize=256m",
      javaOptions in Revolver.reStart += "-Djava.security.krb5.realm= -Djava.security.krb5.kdc=",
      // The only change from sbt-revolver task definition is the "fullClasspath in Compile" so that
      // we can add Spark to the classpath without assembly barfing
      Revolver.reStart <<= InputTask(Actions.startArgsParser) { args =>
        (streams, state, Revolver.reForkOptions, mainClass in Revolver.reStart,
         fullClasspath in Compile, Revolver.reStartArgs, args)
          .map(Actions.restartApp)
          .updateState(Actions.registerAppProcess)
          .dependsOn(products in Compile)
      } )
  ) dependsOn(akkaApp)

  lazy val jobServerTestJar = Project(id = "job-server-tests", base = file("job-server-tests"),
    settings = commonSettings210 ++ Seq(libraryDependencies ++= sparkDeps,
                                        publish      := {},
                                        exportJars := true)   // use the jar instead of target/classes
  )

  // This meta-project aggregates all of the sub-projects and can be used to compile/test/style check
  // all of them with a single command.
  //
  // Note: SBT's default project is the one with the first lexicographical variable name, so we
  // prepend "aaa" to the project name here.
  lazy val aaaMasterProject = Project(
    id = "master", base = file("master")
  ) aggregate(jobServer, jobServerTestJar, akkaApp
  ) settings(
      parallelExecution in Test := false,
      publish      := {},
      concurrentRestrictions := Seq(
        Tags.limit(Tags.CPU, java.lang.Runtime.getRuntime().availableProcessors()),
        // limit to 1 concurrent test task, even across sub-projects
        // Note: some components of tests seem to have the "Untagged" tag rather than "Test" tag.
        // So, we limit the sum of "Test", "Untagged" tags to 1 concurrent
        Tags.limitSum(1, Tags.Test, Tags.Untagged))
  )

  // To add an extra jar to the classpath when doing "re-start" for quick development, set the
  // env var EXTRA_JAR to the absolute full path to the jar
  lazy val extraJarPaths = Option(System.getenv("EXTRA_JAR"))
                             .map(jarpath => Seq(Attributed.blank(file(jarpath))))
                             .getOrElse(Nil)

  lazy val commonSettings210 = Defaults.defaultSettings ++ dirSettings ++ Seq(
    organization := "ooyala.cnd",
    version      := "0.3.0",
    crossPaths   := false,
    scalaVersion := "2.10.2",
    scalaBinaryVersion := "2.10",

    // In Scala 2.10, certain language features are disabled by default, such as implicit conversions.
    // Need to pass in language options or import scala.language.* to enable them.
    // See SIP-18 (https://docs.google.com/document/d/1nlkvpoIRkx7at1qJEZafJwthZ3GeIklTFhqmXMvTX9Q/edit)
    scalacOptions := Seq("-deprecation", "-feature",
                         "-language:implicitConversions", "-language:postfixOps"),
    resolvers    ++= Dependencies.repos,
    libraryDependencies ++= commonDeps,
    parallelExecution in Test := false,
    // We need to exclude jms/jmxtools/etc because it causes undecipherable SBT errors  :(
    ivyXML :=
      <dependencies>
        <exclude module="jms"/>
        <exclude module="jmxtools"/>
        <exclude module="jmxri"/>
      </dependencies>
  ) ++ scalariformPrefs ++ ScalastylePlugin.Settings

  // change to scalariformSettings for auto format on compile; defaultScalariformSettings to disable
  // See https://github.com/mdr/scalariform for formatting options
  lazy val scalariformPrefs = defaultScalariformSettings ++ Seq(
    ScalariformKeys.preferences := FormattingPreferences()
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(PreserveDanglingCloseParenthesis, false)
  )

  // This is here so we can easily switch back to Logback when Spark fixes its log4j dependency.
  lazy val jobServerLogbackLogging = "-Dlogback.configurationFile=config/logback-local.xml"
  lazy val jobServerLogging = "-Dlog4j.configuration=config/log4j-local.properties"
}
