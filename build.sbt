import LibraryManifestGenerator.BundledLibrary
import org.enso.build.BenchTasks._
import org.enso.build.WithDebugCommand
import sbt.Keys.{libraryDependencies, scalacOptions}
import sbt.addCompilerPlugin
import sbt.complete.DefaultParsers._
import sbt.complete.Parser
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import src.main.scala.licenses.{DistributionDescription, SBTDistributionComponent}

import java.io.File

// ============================================================================
// === Global Configuration ===================================================
// ============================================================================

val scalacVersion = "2.13.8"
val graalVersion = "21.3.0"
val javaVersion = "11"
val defaultDevEnsoVersion = "0.0.0-dev"
val ensoVersion = sys.env.getOrElse(
  "ENSO_VERSION",
  defaultDevEnsoVersion
) // Note [Engine And Launcher Version]
val currentEdition = sys.env.getOrElse(
  "ENSO_EDITION",
  defaultDevEnsoVersion
) // Note [Default Editions]

// Note [Stdlib Version]
val stdLibVersion       = defaultDevEnsoVersion
val targetStdlibVersion = ensoVersion

/* Note [Engine And Launcher Version]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Currently both Engine and Launcher versions are tied to each other - each new
 * releases contains the Engine and the Launcher and thus the version number is
 * shared. If the version numbers ever diverge, make sure to update the build
 * scripts at .github/workflows accordingly.
 */

/* Note [Default Editions]
 * ~~~~~~~~~~~~~~~~~~~~~~~
 * Currently, the default edition to use is inferred based on the engine
 * version. Each Enso version has an associated default edition name and the
 * `currentEdition` field specifies the default edition name for the upcoming
 * release.
 *
 * Thus the `library-manager` needs to depend on the `version-output` to get
 * this defaults from the build metadata.
 *
 * In the future we may automate generating this edition number when cutting a
 * release.
 */

/* Note [Stdlib Version]
 * ~~~~~~~~~~~~~~~~~~~~~
 * The `stdlibVersion` variable stores the version at which standard library is
 * stored within the source tree, which is currently set to a constant of
 * `0.0.0-dev`.
 *
 * When distributions are built, the library versions are updated to match the
 * current Enso version.
 */

ThisBuild / organization := "org.enso"
ThisBuild / scalaVersion := scalacVersion

/* Tag limiting the concurrent access to tools/simple-library-server in tests.
 */
val simpleLibraryServerTag = Tags.Tag("simple-library-server")
Global / concurrentRestrictions += Tags.limit(simpleLibraryServerTag, 1)

lazy val gatherLicenses =
  taskKey[Unit]("Gathers licensing information for relevant dependencies")
gatherLicenses := {
  val _ = GatherLicenses.run.value
}
lazy val verifyLicensePackages =
  taskKey[Unit](
    "Verifies if the license package has been generated, " +
    "has no warnings and is up-to-date with dependencies."
  )
verifyLicensePackages := GatherLicenses.verifyReports.value
lazy val verifyGeneratedPackage =
  inputKey[Unit](
    "Verifies if the license package in a generated distribution is " +
    "up-to-date with the one from the report."
  )
verifyGeneratedPackage := GatherLicenses.verifyGeneratedPackage.evaluated

def makeStdLibDistribution(
  name: String,
  components: Seq[SBTDistributionComponent]
): DistributionDescription =
  Distribution(
    name,
    file(s"distribution/lib/Standard/$name/$stdLibVersion/THIRD-PARTY"),
    components
  )

GatherLicenses.distributions := Seq(
  Distribution(
    "launcher",
    file("distribution/launcher/THIRD-PARTY"),
    Distribution.sbtProjects(launcher)
  ),
  Distribution(
    "engine",
    file("distribution/engine/THIRD-PARTY"),
    Distribution.sbtProjects(
      runtime,
      `engine-runner`,
      `language-server`
    )
  ),
  Distribution(
    "project-manager",
    file("distribution/project-manager/THIRD-PARTY"),
    Distribution.sbtProjects(`project-manager`)
  ),
  makeStdLibDistribution("Base", Distribution.sbtProjects(`std-base`)),
  makeStdLibDistribution(
    "Google_Api",
    Distribution.sbtProjects(`std-google-api`)
  ),
  makeStdLibDistribution("Table", Distribution.sbtProjects(`std-table`)),
  makeStdLibDistribution("Database", Distribution.sbtProjects(`std-database`)),
  makeStdLibDistribution("Image", Distribution.sbtProjects(`std-image`))
)

GatherLicenses.licenseConfigurations := Set("compile")
GatherLicenses.configurationRoot := file("tools/legal-review")

lazy val openLegalReviewReport =
  taskKey[Unit](
    "Gathers licensing information for relevant dependencies and opens the " +
    "report in review mode in the browser."
  )
openLegalReviewReport := {
  val _ = gatherLicenses.value
  GatherLicenses.runReportServer()
}

lazy val analyzeDependency = inputKey[Unit]("...")
analyzeDependency := GatherLicenses.analyzeDependency.evaluated

val packageBuilder = new DistributionPackage.Builder(
  ensoVersion      = ensoVersion,
  graalVersion     = graalVersion,
  graalJavaVersion = javaVersion,
  artifactRoot     = file("built-distribution")
)

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / excludeLintKeys += logManager

// ============================================================================
// === Compiler Options =======================================================
// ============================================================================

ThisBuild / javacOptions ++= Seq(
  "-encoding",       // Provide explicit encoding (the next line)
  "UTF-8",           // Specify character encoding used by Java source files
  "-deprecation",    // Shows a description of each use or override of a deprecated member or class
  "-g",              // Include debugging information
  "-Xlint:unchecked" // Enable additional warnings
)

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",                       // Emit warning and location for usages of deprecated APIs.
  "-encoding",                          // Provide explicit encoding (the next line)
  "utf-8",                              // Specify character encoding used by Scala source files.
  "-explaintypes",                      // Explain type errors in more detail.
  "-feature",                           // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials",             // Existential types (besides wildcard types) can be written and inferred
  "-language:experimental.macros",      // Allow macro definition (besides implementation and application)
  "-language:higherKinds",              // Allow higher-kinded types
  "-language:implicitConversions",      // Allow definition of implicit functions called views
  "-unchecked",                         // Enable additional warnings where generated code depends on assumptions.
  "-Vimplicits",                        // Prints implicit resolution chains when no implicit can be found.
  "-Vtype-diffs",                       // Prints type errors as coloured diffs between types.
  "-Xcheckinit",                        // Wrap field accessors to throw an exception on uninitialized access.
  "-Xfatal-warnings",                   // Make warnings fatal so they don't make it onto main (use @nowarn for local suppression)
  "-Xlint:adapted-args",                // Warn if an argument list is modified to match the receiver.
  "-Xlint:constant",                    // Evaluation of a constant arithmetic expression results in an error.
  "-Xlint:delayedinit-select",          // Selecting member of DelayedInit.
  "-Xlint:doc-detached",                // A Scaladoc comment appears to be detached from its element.
  "-Xlint:inaccessible",                // Warn about inaccessible types in method signatures.
  "-Xlint:infer-any",                   // Warn when a type argument is inferred to be `Any`.
  "-Xlint:missing-interpolator",        // A string literal appears to be missing an interpolator id.
  "-Xlint:nullary-unit",                // Warn when nullary methods return Unit.
  "-Xlint:option-implicit",             // Option.apply used implicit view.
  "-Xlint:package-object-classes",      // Class or object defined in package object.
  "-Xlint:poly-implicit-overload",      // Parameterized overloaded implicit methods are not visible as view bounds.
  "-Xlint:private-shadow",              // A private field (or class parameter) shadows a superclass field.
  "-Xlint:stars-align",                 // Pattern sequence wildcard must align with sequence component.
  "-Xlint:type-parameter-shadow",       // A local type parameter shadows a type already in scope.
  "-Xmacro-settings:-logging@org.enso", // Disable the debug logging globally.
  "-Ywarn-dead-code",                   // Warn when dead code is identified.
  "-Ywarn-extra-implicit",              // Warn when more than one implicit parameter section is defined.
  "-Ywarn-numeric-widen",               // Warn when numerics are widened.
  "-Ywarn-unused:implicits",            // Warn if an implicit parameter is unused.
  "-Ywarn-unused:imports",              // Warn if an import selector is not referenced.
  "-Ywarn-unused:locals",               // Warn if a local definition is unused.
  "-Ywarn-unused:params",               // Warn if a value parameter is unused.
  "-Ywarn-unused:patvars",              // Warn if a variable bound in a pattern is unused.
  "-Ywarn-unused:privates"              // Warn if a private member is unused.
)

ThisBuild / Test / testOptions += Tests.Argument(
  "-oI"
)

val jsSettings = Seq(
  scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
)

Compile / console / scalacOptions ~= (_ filterNot (_ == "-Xfatal-warnings"))

// ============================================================================
// === Benchmark Configuration ================================================
// ============================================================================

lazy val Benchmark = config("bench") extend sbt.Test

// Native Image Generation
lazy val rebuildNativeImage = taskKey[Unit]("Force to rebuild native image")
lazy val buildNativeImage =
  taskKey[Unit]("Ensure that the Native Image is built.")

// Bootstrap task
lazy val bootstrap =
  taskKey[Unit]("Prepares Truffle JARs that are required by the sbt JVM")
bootstrap := {}

// ============================================================================
// === Global Project =========================================================
// ============================================================================

lazy val enso = (project in file("."))
  .settings(version := "0.1")
  .aggregate(
    `interpreter-dsl`,
    `json-rpc-server-test`,
    `json-rpc-server`,
    `language-server`,
    `parser-service`,
    `docs-generator`,
    `polyglot-api`,
    `project-manager`,
    `syntax-definition`.jvm,
    `text-buffer`,
    flexer.jvm,
    graph,
    logger.jvm,
    pkg,
    cli,
    `task-progress-notifications`,
    `profiling-utils`,
    `logging-utils`,
    `logging-service`,
    `logging-truffle-connector`,
    `locking-test-helper`,
    `akka-native`,
    `version-output`,
    `engine-runner`,
    runtime,
    searcher,
    launcher,
    downloader,
    `runtime-language-epb`,
    `runtime-instrument-id-execution`,
    `runtime-instrument-repl-debugger`,
    `runtime-instrument-runtime-server`,
    `runtime-with-instruments`,
    `runtime-version-manager`,
    `runtime-version-manager-test`,
    editions,
    `distribution-manager`,
    `edition-updater`,
    `edition-uploader`,
    `library-manager`,
    `library-manager-test`,
    `connected-lock-manager`,
    syntax.jvm,
    testkit,
    `std-base`,
    `std-database`,
    `std-google-api`,
    `std-image`,
    `std-table`
  )
  .settings(Global / concurrentRestrictions += Tags.exclusive(Exclusive))
  .settings(
    commands ++= Seq(packageBuilder.makePackages, packageBuilder.makeBundles)
  )

// ============================================================================
// === Dependency Versions ====================================================
// ============================================================================

/* Note [Dependency Versions]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Please maintain the following section in alphabetical order for the bundles
 * of dependencies. Additionally, please keep the 'Other' subsection in
 * alphabetical order.
 *
 * Furthermore, please keep the following in mind:
 * - Wherever possible, we should use the same version of a dependency
 *   throughout the project.
 * - If you need to include a new dependency, please define its version in this
 *   section.
 * - If that version is not the latest, please include a note explaining why
 *   this is the case.
 * - If, for some reason, you need to use a dependency version other than the
 *   global one, please include a note explaining why this is the case, and the
 *   circumstances under which the dependency could be upgraded to use the
 *   global version
 */

// === Akka ===================================================================

def akkaPkg(name: String)     = akkaURL                       %% s"akka-$name" % akkaVersion
def akkaHTTPPkg(name: String) = akkaURL                       %% s"akka-$name" % akkaHTTPVersion
val akkaURL                   = "com.typesafe.akka"
val akkaVersion               = "2.6.19"
val akkaHTTPVersion           = "10.2.9"
val akkaMockSchedulerVersion  = "0.5.5"
val logbackClassicVersion     = "1.2.11"
val akkaActor                 = akkaPkg("actor")
val akkaStream                = akkaPkg("stream")
val akkaTyped                 = akkaPkg("actor-typed")
val akkaTestkit               = akkaPkg("testkit")
val akkaSLF4J                 = akkaPkg("slf4j")
val akkaTestkitTyped          = akkaPkg("actor-testkit-typed") % Test
val akkaHttp                  = akkaHTTPPkg("http")
val akkaSpray                 = akkaHTTPPkg("http-spray-json")
val akkaTest = Seq(
  "ch.qos.logback" % "logback-classic" % logbackClassicVersion % Test
)
val akka =
  Seq(
    akkaActor,
    akkaStream,
    akkaHttp,
    akkaSpray,
    akkaTyped
  )

// === Cats ===================================================================

val catsVersion    = "2.8.0"
val kittensVersion = "2.3.2"
val cats = {
  Seq(
    "org.typelevel" %% "cats-core"   % catsVersion,
    "org.typelevel" %% "cats-effect" % catsVersion,
    "org.typelevel" %% "cats-free"   % catsVersion,
    "org.typelevel" %% "cats-macros" % catsVersion,
    "org.typelevel" %% "kittens"     % kittensVersion
  )
}

// === Circe ==================================================================

val circeVersion              = "0.14.2"
val circeYamlVersion          = "0.14.1"
val enumeratumCirceVersion    = "1.7.0"
val circeGenericExtrasVersion = "0.14.2"
val circe = Seq("circe-core", "circe-generic", "circe-parser")
  .map("io.circe" %% _ % circeVersion)

// === Commons ================================================================

val commonsCollectionsVersion = "4.4"
val commonsLangVersion        = "3.12.0"
val commonsIoVersion          = "2.11.0"
val commonsTextVersion        = "1.8"
val commonsMathVersion        = "3.6.1"
val commonsCompressVersion    = "1.21"
val commonsCliVersion         = "1.5.0"
val commons = Seq(
  "org.apache.commons" % "commons-collections4" % commonsCollectionsVersion,
  "org.apache.commons" % "commons-lang3"        % commonsLangVersion,
  "commons-io"         % "commons-io"           % commonsIoVersion,
  "org.apache.commons" % "commons-text"         % commonsTextVersion,
  "org.apache.commons" % "commons-math3"        % commonsMathVersion,
  "commons-cli"        % "commons-cli"          % commonsCliVersion
)

// === Jackson ================================================================

val jacksonVersion = "2.13.3"
val jackson = Seq(
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
  "com.fasterxml.jackson.core"       % "jackson-databind"        % jacksonVersion,
  "com.fasterxml.jackson.module"    %% "jackson-module-scala"    % jacksonVersion
)

// === JAXB ================================================================

val jaxbVersion = "4.0.0"
val jaxb = Seq(
  "jakarta.xml.bind" % "jakarta.xml.bind-api" % jaxbVersion % Benchmark,
  "com.sun.xml.bind" % "jaxb-impl"            % jaxbVersion % Benchmark
)

// === JMH ====================================================================

val jmhVersion = "1.35"
val jmh = Seq(
  "org.openjdk.jmh" % "jmh-core"                 % jmhVersion % Benchmark,
  "org.openjdk.jmh" % "jmh-generator-annprocess" % jmhVersion % Benchmark
)

// === Monocle ================================================================

val monocleVersion = "2.1.0"
val monocle = {
  Seq(
    "com.github.julien-truffaut" %% "monocle-core"  % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-law"   % monocleVersion % "test"
  )
}

// === Scala Compiler =========================================================

val scalaCompiler = Seq(
  "org.scala-lang" % "scala-reflect"  % scalacVersion,
  "org.scala-lang" % "scala-compiler" % scalacVersion
)

// === std-lib ================================================================

val icuVersion = "71.1"

// === ZIO ====================================================================

val zioVersion            = "1.0.12"
val zioInteropCatsVersion = "3.2.9.0"
val zio = Seq(
  "dev.zio" %% "zio"              % zioVersion,
  "dev.zio" %% "zio-interop-cats" % zioInteropCatsVersion
)

// === Other ==================================================================

val bcpkixJdk15Version      = "1.70"
val bumpVersion             = "0.1.3"
val declineVersion          = "2.3.0"
val directoryWatcherVersion = "0.9.10"
val flatbuffersVersion      = "1.12.0"
val guavaVersion            = "31.1-jre"
val jlineVersion            = "3.21.0"
val kindProjectorVersion    = "0.13.2"
val mockitoScalaVersion     = "1.17.12"
val newtypeVersion          = "0.4.4"
val pprintVersion           = "0.7.3"
val pureconfigVersion       = "0.17.1"
val scalacheckVersion       = "1.16.0"
val scalacticVersion        = "3.3.0-SNAP3"
val scalaLoggingVersion     = "3.9.4"
val scalameterVersion       = "0.19"
val scalatagsVersion        = "0.11.1"
val scalatestVersion        = "3.3.0-SNAP3"
val shapelessVersion        = "2.4.0-M1"
val slf4jVersion            = "1.7.36"
val slickVersion            = "3.3.3"
val sqliteVersion           = "3.36.0.3"
val tikaVersion             = "2.4.1"
val typesafeConfigVersion   = "1.4.2"
val junitVersion            = "4.13.2"
val netbeansApiVersion      = "RELEASE140"

// ============================================================================
// === Internal Libraries =====================================================
// ============================================================================

lazy val logger = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("lib/scala/logger"))
  .settings(
    version := "0.1",
    libraryDependencies ++= scalaCompiler
  )
  .jsSettings(jsSettings)

lazy val flexer = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("lib/scala/flexer"))
  .dependsOn(logger)
  .settings(
    version := "0.1",
    resolvers ++= Resolver.sonatypeOssRepos("releases"),
    libraryDependencies ++= scalaCompiler ++ Seq(
      "com.google.guava" % "guava"     % guavaVersion exclude ("com.google.code.findbugs", "jsr305"),
      "org.typelevel"  %%% "cats-core" % catsVersion,
      "org.typelevel"  %%% "kittens"   % kittensVersion
    )
  )
  .jsSettings(jsSettings)

lazy val `syntax-definition` = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("lib/scala/syntax/definition"))
  .dependsOn(logger, flexer)
  .settings(
    scalacOptions ++= Seq("-Ypatmat-exhaust-depth", "off"),
    libraryDependencies ++= monocle ++ scalaCompiler ++ Seq(
      "org.typelevel" %%% "cats-core"     % catsVersion,
      "org.typelevel" %%% "kittens"       % kittensVersion,
      "com.lihaoyi"   %%% "scalatags"     % scalatagsVersion,
      "io.circe"      %%% "circe-core"    % circeVersion,
      "io.circe"      %%% "circe-generic" % circeVersion,
      "io.circe"      %%% "circe-parser"  % circeVersion
    )
  )
  .jsSettings(jsSettings)

lazy val syntax = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("lib/scala/syntax/specialization"))
  .dependsOn(logger, flexer, `syntax-definition`)
  .configs(Test)
  .configs(Benchmark)
  .settings(
    commands += WithDebugCommand.withDebug,
    Test / fork := true,
    testFrameworks := Nil,
    scalacOptions ++= Seq("-Ypatmat-exhaust-depth", "off"),
    Compile / run / mainClass := Some("org.enso.syntax.text.Main"),
    version := "0.1",
    logBuffered := false,
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest"     % scalatestVersion % Test,
      "com.lihaoyi"   %%% "pprint"        % pprintVersion,
      "io.circe"      %%% "circe-core"    % circeVersion,
      "io.circe"      %%% "circe-generic" % circeVersion,
      "io.circe"      %%% "circe-parser"  % circeVersion
    ),
    (Compile / compile) := (Compile / compile)
      .dependsOn(RecompileParser.run(`syntax-definition`))
      .value,
    (Test / compile) := (Test / compile)
      .dependsOn(RecompileParser.run(`syntax-definition`))
      .value,
    (Benchmark / compile) := (Benchmark / compile)
      .dependsOn(RecompileParser.run(`syntax-definition`))
      .value
  )
  .jvmSettings(
    inConfig(Benchmark)(Defaults.testSettings),
    Benchmark / unmanagedSourceDirectories +=
      baseDirectory.value.getParentFile / "shared/src/bench/scala",
    libraryDependencies +=
      "com.storm-enroute" %% "scalameter" % scalameterVersion % "bench",
    testFrameworks := List(
      new TestFramework("org.scalatest.tools.Framework"),
      new TestFramework("org.scalameter.ScalaMeterFramework")
    ),
    bench := (Benchmark / test).tag(Exclusive).value
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    testFrameworks := List(new TestFramework("org.scalatest.tools.Framework")),
    Compile / fullOptJS / artifactPath := file("target/scala-parser.js"),
    libraryDependencies +=
      "org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0"
  )

lazy val `lexer-bench` =
  (project in file("lib/scala/syntax/specialization/lexer-bench"))
    .settings(
      commands += WithDebugCommand.withDebug,
      inConfig(Compile)(truffleRunOptionsSettings),
      inConfig(Benchmark)(Defaults.testSettings),
      Test / parallelExecution := false,
      Test / logBuffered := false,
      Test / fork := true,
      libraryDependencies ++= jmh
    )
    .configs(Test)
    .configs(Benchmark)
    .dependsOn(syntax.jvm)
    .dependsOn(flexer.jvm)
    .settings(
      javaOptions ++= Seq(
        "-Xms4096m",
        "-Xmx4096m",
        "-XX:+FlightRecorder"
      ),
      Benchmark / mainClass := Some("org.openjdk.jmh.Main"),
      bench := Def.task {
        (Benchmark / run).toTask("").value
      },
      benchOnly := Def.inputTaskDyn {
        import complete.Parsers.spaceDelimited
        val name = spaceDelimited("<name>").parsed match {
          case List(name) => name
          case _ =>
            throw new IllegalArgumentException("Expected one argument.")
        }
        Def.task {
          (Benchmark / testOnly).toTask(" -- -z " + name).value
        }
      }.evaluated,
      Benchmark / parallelExecution := false
    )

lazy val `parser-service` = (project in file("lib/scala/parser-service"))
  .dependsOn(syntax.jvm)
  .settings(
    libraryDependencies ++= akka ++ akkaTest,
    mainClass := Some("org.enso.ParserServiceMain")
  )

lazy val `docs-generator` = (project in file("lib/scala/docs-generator"))
  .dependsOn(syntax.jvm)
  .dependsOn(cli)
  .dependsOn(`version-output`)
  .configs(Benchmark)
  .settings(
    libraryDependencies ++= Seq(
      "commons-cli" % "commons-cli" % commonsCliVersion
    ),
    mainClass := Some("org.enso.docs.generator.Main"),
    inConfig(Benchmark)(Defaults.testSettings),
    Benchmark / unmanagedSourceDirectories +=
      baseDirectory.value.getParentFile / "bench" / "scala",
    libraryDependencies ++= Seq(
      "com.storm-enroute" %% "scalameter" % scalameterVersion % "bench",
      "org.scalatest"     %% "scalatest"  % scalatestVersion  % Test
    ),
    testFrameworks := List(
      new TestFramework("org.scalatest.tools.Framework"),
      new TestFramework("org.scalameter.ScalaMeterFramework")
    ),
    bench := (Benchmark / test).tag(Exclusive).value
  )

lazy val `text-buffer` = project
  .in(file("lib/scala/text-buffer"))
  .configs(Test)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"   %% "cats-core"      % catsVersion,
      "org.bouncycastle" % "bcpkix-jdk15on" % bcpkixJdk15Version,
      "org.scalatest"   %% "scalatest"      % scalatestVersion  % Test,
      "org.scalacheck"  %% "scalacheck"     % scalacheckVersion % Test
    )
  )

lazy val graph = (project in file("lib/scala/graph/"))
  .dependsOn(logger.jvm)
  .configs(Test)
  .settings(
    version := "0.1",
    resolvers ++= (
      Resolver.sonatypeOssRepos("releases") ++
      Resolver.sonatypeOssRepos("snapshots")
    ),
    scalacOptions += "-Ymacro-annotations",
    libraryDependencies ++= scalaCompiler ++ Seq(
      "com.chuusai"                %% "shapeless"     % shapelessVersion,
      "io.estatico"                %% "newtype"       % newtypeVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion  % Test,
      "org.scalacheck"             %% "scalacheck"    % scalacheckVersion % Test,
      "com.github.julien-truffaut" %% "monocle-core"  % monocleVersion,
      "org.apache.commons"          % "commons-lang3" % commonsLangVersion
    ),
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % kindProjectorVersion cross CrossVersion.full
    )
  )

lazy val pkg = (project in file("lib/scala/pkg"))
  .settings(
    Compile / run / mainClass := Some("org.enso.pkg.Main"),
    version := "0.1",
    libraryDependencies ++= circe ++ Seq(
      "org.scalatest" %% "scalatest"  % scalatestVersion % Test,
      "io.circe"      %% "circe-yaml" % circeYamlVersion, // separate from other circe deps because its independent project with its own versioning
      "commons-io"     % "commons-io" % commonsIoVersion
    )
  )
  .dependsOn(editions)

lazy val `akka-native` = project
  .in(file("lib/scala/akka-native"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      akkaActor
    ),
    // Note [Native Image Workaround for GraalVM 20.2]
    libraryDependencies += "org.graalvm.nativeimage" % "svm" % graalVersion % "provided"
  )

lazy val `profiling-utils` = project
  .in(file("lib/scala/profiling-utils"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "org.netbeans.api" % "org-netbeans-modules-sampler" % netbeansApiVersion
      exclude ("org.netbeans.api", "org-openide-loaders")
      exclude ("org.netbeans.api", "org-openide-nodes")
      exclude("org.netbeans.api", "org-netbeans-api-progress-nb")
      exclude("org.netbeans.api", "org-netbeans-api-progress")
      exclude("org.netbeans.api", "org-openide-util-lookup")
      exclude("org.netbeans.api", "org-openide-util")
      exclude("org.netbeans.api", "org-openide-dialogs")
      exclude ("org.netbeans.api", "org-openide-filesystems")
      exclude ("org.netbeans.api", "org-openide-util-ui")
      exclude ("org.netbeans.api", "org-openide-awt")
      exclude ("org.netbeans.api", "org-openide-modules")
      exclude ("org.netbeans.api", "org-netbeans-api-annotations-common")
    )
  )

lazy val `logging-utils` = project
  .in(file("lib/scala/logging-utils"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalatestVersion % Test
    )
  )

lazy val `logging-service` = project
  .in(file("lib/scala/logging-service"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "org.slf4j"                   % "slf4j-api"     % slf4jVersion,
      "com.typesafe"                % "config"        % typesafeConfigVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      akkaStream,
      akkaHttp,
      "io.circe"              %%% "circe-core"   % circeVersion,
      "io.circe"              %%% "circe-parser" % circeVersion,
      "org.scalatest"          %% "scalatest"    % scalatestVersion % Test,
      "org.graalvm.nativeimage" % "svm"          % graalVersion     % "provided"
    )
  )
  .settings(
    if (Platform.isWindows)
      (Compile / unmanagedSourceDirectories) += (Compile / sourceDirectory).value / "java-windows"
    else
      (Compile / unmanagedSourceDirectories) += (Compile / sourceDirectory).value / "java-unix"
  )
  .dependsOn(`akka-native`)
  .dependsOn(`logging-utils`)

lazy val `logging-truffle-connector` = project
  .in(file("lib/scala/logging-truffle-connector"))
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "org.slf4j"           % "slf4j-api"   % slf4jVersion,
      "org.graalvm.truffle" % "truffle-api" % graalVersion % "provided"
    )
  )
  .dependsOn(`logging-utils`)
  .dependsOn(`polyglot-api`)

lazy val cli = project
  .in(file("lib/scala/cli"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= circe ++ Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test,
      "org.typelevel"              %% "cats-core"     % catsVersion
    ),
    Test / parallelExecution := false
  )

lazy val `task-progress-notifications` = project
  .in(file("lib/scala/task-progress-notifications"))
  .configs(Test)
  .settings(
    version := "0.1",
    libraryDependencies ++= Seq(
      "com.beachape"  %% "enumeratum-circe" % enumeratumCirceVersion,
      "org.scalatest" %% "scalatest"        % scalatestVersion % Test
    ),
    Test / parallelExecution := false
  )
  .dependsOn(cli)
  .dependsOn(`json-rpc-server`)

lazy val `version-output` = (project in file("lib/scala/version-output"))
  .settings(
    version := "0.1"
  )
  .settings(
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "buildinfo" / "Info.scala"
      BuildInfo
        .writeBuildInfoFile(
          file                  = file,
          log                   = state.value.log,
          defaultDevEnsoVersion = defaultDevEnsoVersion,
          ensoVersion           = ensoVersion,
          scalacVersion         = scalacVersion,
          graalVersion          = graalVersion,
          currentEdition        = currentEdition
        )
    }.taskValue
  )

lazy val `project-manager` = (project in file("lib/scala/project-manager"))
  .settings(
    (Compile / mainClass) := Some("org.enso.projectmanager.boot.ProjectManager")
  )
  .settings(
    (Compile / run / fork) := true,
    (Test / fork) := true,
    (Compile / run / connectInput) := true,
    libraryDependencies ++= akka ++ Seq(akkaTestkit % Test),
    libraryDependencies ++= circe,
    libraryDependencies ++= Seq(
      "com.typesafe"                % "config"              % typesafeConfigVersion,
      "com.github.pureconfig"      %% "pureconfig"          % pureconfigVersion,
      "com.typesafe.scala-logging" %% "scala-logging"       % scalaLoggingVersion,
      "dev.zio"                    %% "zio"                 % zioVersion,
      "dev.zio"                    %% "zio-interop-cats"    % zioInteropCatsVersion,
      "commons-cli"                 % "commons-cli"         % commonsCliVersion,
      "commons-io"                  % "commons-io"          % commonsIoVersion,
      "org.apache.commons"          % "commons-lang3"       % commonsLangVersion,
      "com.beachape"               %% "enumeratum-circe"    % enumeratumCirceVersion,
      "com.miguno.akka"            %% "akka-mock-scheduler" % akkaMockSchedulerVersion % Test,
      "org.mockito"                %% "mockito-scala"       % mockitoScalaVersion      % Test
    ),
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % kindProjectorVersion cross CrossVersion.full
    )
  )
  .settings(
    assembly / assemblyJarName := "project-manager.jar",
    assembly / test := {},
    assembly / assemblyOutputPath := file("project-manager.jar"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".DSA") =>
        MergeStrategy.discard
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".SF") =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF", xs @ _*) =>
        MergeStrategy.discard
      case "application.conf" => MergeStrategy.concat
      case "reference.conf"   => MergeStrategy.concat
      case _                  => MergeStrategy.first
    },
    (Test / test) := (Test / test).dependsOn(`engine-runner` / assembly).value,
    rebuildNativeImage := NativeImage
      .buildNativeImage(
        "project-manager",
        staticOnLinux       = true,
        initializeAtRuntime = Seq("scala.util.Random")
      )
      .dependsOn(VerifyReflectionSetup.run)
      .dependsOn(assembly)
      .value,
    buildNativeImage := NativeImage
      .incrementalNativeImageBuild(
        rebuildNativeImage,
        "project-manager"
      )
      .value
  )
  .dependsOn(`akka-native`)
  .dependsOn(`version-output`)
  .dependsOn(editions)
  .dependsOn(`edition-updater`)
  .dependsOn(cli)
  .dependsOn(`task-progress-notifications`)
  .dependsOn(`polyglot-api`)
  .dependsOn(`runtime-version-manager`)
  .dependsOn(`library-manager`)
  .dependsOn(pkg)
  .dependsOn(`json-rpc-server`)
  .dependsOn(`json-rpc-server-test` % Test)
  .dependsOn(testkit % Test)
  .dependsOn(`runtime-version-manager-test` % Test)

/* Note [Classpath Separation]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Projects using the language runtime do not depend on it directly, but instead
 * the language runtime is put on the Truffle classpath, rather than the
 * standard classpath. This is the recommended way of handling this and we
 * strive to use such structure everywhere.
 * See
 * https://www.graalvm.org/docs/graalvm-as-a-platform/implement-language#graalvm
 *
 * Currently the only exception to this are the tests of the runtime project
 * which have classpath separation disabled, because they need direct access to
 * the runtime's instruments.
 *
 * To ensure correct handling of dependencies by sbt, the classpath appended to
 * Java options, should be based on `(runtime / Compile / fullClasspath).value`
 * wherever possible. Using a key from the runtime project enables sbt to see
 * the dependency.
 *
 * Assembly tasks that build JAR files which need `runtime.jar` to run should
 * also add a dependency on `runtime / assembly`.
 */

lazy val `json-rpc-server` = project
  .in(file("lib/scala/json-rpc-server"))
  .settings(
    libraryDependencies ++= akka ++ akkaTest,
    libraryDependencies ++= circe,
    libraryDependencies ++= Seq(
      "io.circe"                   %% "circe-literal" % circeVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      akkaTestkit                   % Test,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )

lazy val `json-rpc-server-test` = project
  .in(file("lib/scala/json-rpc-server-test"))
  .settings(
    libraryDependencies ++= akka,
    libraryDependencies ++= circe,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-literal" % circeVersion,
      akkaTestkit,
      "org.scalatest" %% "scalatest" % scalatestVersion
    )
  )
  .dependsOn(`json-rpc-server`)

lazy val testkit = project
  .in(file("lib/scala/testkit"))
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-lang3" % commonsLangVersion,
      "commons-io"         % "commons-io"    % commonsIoVersion,
      "org.scalatest"     %% "scalatest"     % scalatestVersion
    )
  )

lazy val searcher = project
  .in(file("lib/scala/searcher"))
  .configs(Test)
  .settings(
    libraryDependencies ++= jmh ++ Seq(
      "com.typesafe.slick" %% "slick"           % slickVersion,
      "org.xerial"          % "sqlite-jdbc"     % sqliteVersion,
      "ch.qos.logback"      % "logback-classic" % logbackClassicVersion % Test,
      "org.scalatest"      %% "scalatest"       % scalatestVersion      % Test
    )
  )
  .configs(Benchmark)
  .settings(
    inConfig(Benchmark)(Defaults.testSettings),
    Benchmark / fork := true
  )
  .dependsOn(testkit % Test)
  .dependsOn(`polyglot-api`)

lazy val `interpreter-dsl` = (project in file("lib/scala/interpreter-dsl"))
  .settings(
    version := "0.1",
    frgaalJavaCompilerSetting,
    Compile / javacOptions := ((Compile / javacOptions).value ++
    // Only run ServiceProvider processor and ignore those defined in META-INF, thus
    // fixing incremental compilation setup
    Seq(
      "-processor",
      "org.netbeans.modules.openide.util.ServiceProviderProcessor"
    )),
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-lang3"           % commonsLangVersion,
      "org.netbeans.api"   % "org-openide-util-lookup" % netbeansApiVersion,
      "com.google.guava"   % "guava"                   % guavaVersion exclude ("com.google.code.findbugs", "jsr305")
    )
  )

// ============================================================================
// === Sub-Projects ===========================================================
// ============================================================================

val truffleRunOptions = if (java.lang.Boolean.getBoolean("bench.compileOnly")) {
  Seq(
    "-Dpolyglot.engine.IterativePartialEscape=true",
    "-Dpolyglot.engine.BackgroundCompilation=false",
    "-Dbench.compileOnly=true"
  )
} else {
  Seq(
    "-Dpolyglot.engine.IterativePartialEscape=true",
    "-Dpolyglot.engine.BackgroundCompilation=false"
  )
}


val truffleRunOptionsSettings = Seq(
  fork := true,
  javaOptions ++= truffleRunOptions
)

lazy val `polyglot-api` = project
  .in(file("engine/polyglot-api"))
  .settings(
    Test / fork := true,
    commands += WithDebugCommand.withDebug,
    Test / envVars ++= distributionEnvironmentOverrides,
    Test / javaOptions ++= {
      // Note [Classpath Separation]
      val runtimeClasspath =
        (LocalProject("runtime") / Compile / fullClasspath).value
          .map(_.data)
          .mkString(File.pathSeparator)
      Seq(s"-Dtruffle.class.path.append=$runtimeClasspath")
    },
    libraryDependencies ++= Seq(
      "org.graalvm.sdk"        % "polyglot-tck"     % graalVersion      % "provided",
      "com.google.flatbuffers" % "flatbuffers-java" % flatbuffersVersion,
      "org.scalatest"         %% "scalatest"        % scalatestVersion  % Test,
      "org.scalacheck"        %% "scalacheck"       % scalacheckVersion % Test
    ),
    libraryDependencies ++= jackson,
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % kindProjectorVersion cross CrossVersion.full
    ),
    GenerateFlatbuffers.flatcVersion := flatbuffersVersion,
    Compile / sourceGenerators += GenerateFlatbuffers.task
  )
  .dependsOn(pkg)
  .dependsOn(`text-buffer`)
  .dependsOn(`logging-utils`)
  .dependsOn(testkit % Test)

lazy val `language-server` = (project in file("engine/language-server"))
  .settings(
    libraryDependencies ++= akka ++ circe ++ Seq(
      "com.typesafe.scala-logging" %% "scala-logging"        % scalaLoggingVersion,
      "io.circe"                   %% "circe-generic-extras" % circeGenericExtrasVersion,
      "io.circe"                   %% "circe-literal"        % circeVersion,
      "dev.zio"                    %% "zio"                  % zioVersion,
      "io.methvin"                  % "directory-watcher"    % directoryWatcherVersion,
      "com.beachape"               %% "enumeratum-circe"     % enumeratumCirceVersion,
      "com.google.flatbuffers"      % "flatbuffers-java"     % flatbuffersVersion,
      "commons-io"                  % "commons-io"           % commonsIoVersion,
      akkaTestkit                   % Test,
      "com.typesafe.akka"          %% "akka-http-testkit"    % akkaHTTPVersion   % Test,
      "org.scalatest"              %% "scalatest"            % scalatestVersion  % Test,
      "org.scalacheck"             %% "scalacheck"           % scalacheckVersion % Test,
      "org.graalvm.sdk"             % "polyglot-tck"         % graalVersion      % "provided"
    ),
    Test / testOptions += Tests
      .Argument(TestFrameworks.ScalaCheck, "-minSuccessfulTests", "1000"),
    Test / envVars ++= distributionEnvironmentOverrides,
    GenerateFlatbuffers.flatcVersion := flatbuffersVersion,
    Compile / sourceGenerators += GenerateFlatbuffers.task
  )
  .configs(Benchmark)
  .settings(
    inConfig(Benchmark)(Defaults.testSettings),
    bench := (Benchmark / test).value,
    libraryDependencies += "com.storm-enroute" %% "scalameter" % scalameterVersion % "bench",
    testFrameworks ++= List(
      new TestFramework("org.scalameter.ScalaMeterFramework")
    )
  )
  .settings(
    // These settings are needed by language-server tests that create a runtime context.
    Test / fork := true,
    Test / javaOptions ++= {
      // Note [Classpath Separation]
      val runtimeClasspath =
        (LocalProject("runtime") / Compile / fullClasspath).value
          .map(_.data)
          .mkString(File.pathSeparator)
      Seq(
        s"-Dtruffle.class.path.append=$runtimeClasspath",
        s"-Duser.dir=${file(".").getCanonicalPath}"
      )
    },
    Test / compile := (Test / compile)
      .dependsOn(LocalProject("enso") / updateLibraryManifests)
      .value,
    Test / envVars ++= Map(
      "ENSO_EDITION_PATH" -> file("distribution/editions").getCanonicalPath
    )
  )
  .dependsOn(`json-rpc-server-test` % Test)
  .dependsOn(`json-rpc-server`)
  .dependsOn(`task-progress-notifications`)
  .dependsOn(`library-manager`)
  .dependsOn(`connected-lock-manager`)
  .dependsOn(`edition-updater`)
  .dependsOn(`logging-service`)
  .dependsOn(`polyglot-api`)
  .dependsOn(`searcher`)
  .dependsOn(`text-buffer`)
  .dependsOn(`version-output`)
  .dependsOn(pkg)
  .dependsOn(`docs-generator`)
  .dependsOn(`profiling-utils`)
  .dependsOn(testkit % Test)
  .dependsOn(`library-manager-test` % Test)
  .dependsOn(`runtime-version-manager-test` % Test)

lazy val ast = (project in file("lib/scala/ast"))
  .settings(
    version := ensoVersion,
    Compile / sourceGenerators += GenerateAST.task
  )

lazy val parser = (project in file("lib/scala/parser"))
  .settings(
    fork := true,
    Compile / compile / compileInputs := (Compile / compile / compileInputs)
      .dependsOn(Cargo("build --project parser"))
      .value,
    javaOptions += {
      val root = baseDirectory.value.getParentFile.getParentFile.getParentFile
      s"-Djava.library.path=$root/target/rust/debug"
    },
    libraryDependencies ++= Seq(
      "com.storm-enroute" %% "scalameter" % scalameterVersion % "bench",
      "org.scalatest"    %%% "scalatest"  % scalatestVersion  % Test
    ),
    testFrameworks := List(
      new TestFramework("org.scalatest.tools.Framework"),
      new TestFramework("org.scalameter.ScalaMeterFramework")
    )
  )
  .dependsOn(ast)

lazy val cleanInstruments = taskKey[Unit](
  "Cleans fragile class files to force a full recompilation and preserve" +
  "consistency of instrumentation configuration."
)

/** Overrides for the environment variables related to the distribution, so that
  * a local installation does not interfere with runtime tests.
  */
val distributionEnvironmentOverrides = {
  val fakeDir = file("target/fake_dir").getAbsolutePath
  Map(
    "ENSO_DATA_DIRECTORY"           -> fakeDir,
    "ENSO_CONFIG_DIRECTORY"         -> fakeDir,
    "ENSO_RUNTIME_DIRECTORY"        -> file("target/run").getAbsolutePath,
    "ENSO_LOG_DIRECTORY"            -> file("target/logs").getAbsolutePath,
    "ENSO_HOME"                     -> fakeDir,
    "ENSO_EDITION_PATH"             -> "",
    "ENSO_LIBRARY_PATH"             -> "",
    "ENSO_AUXILIARY_LIBRARY_CACHES" -> ""
  )
}

val frgaalSourceLevel = "19"

/** A setting to replace javac with Frgaal compiler, allowing to use latest Java features in the code
  * and still compile down to JDK 11
  */
lazy val frgaalJavaCompilerSetting = Seq(
  Compile / compile / compilers := FrgaalJavaCompiler.compilers(
    (Compile / dependencyClasspath).value,
    compilers.value,
    javaVersion
  ),
  // This dependency is needed only so that developers don't download Frgaal manually.
  // Sadly it cannot be placed under plugins either because meta dependencies are not easily
  // accessible from the non-meta build definition.
  libraryDependencies += FrgaalJavaCompiler.frgaal,
  // Ensure that our tooling uses the right Java version for checking the code.
  Compile / javacOptions ++= Seq(
    "-source",
    frgaalSourceLevel,
    "--enable-preview"
  )
)

lazy val instrumentationSettings = frgaalJavaCompilerSetting ++ Seq(
  version := ensoVersion,
  commands += WithDebugCommand.withDebug,
  Compile / logManager :=
    sbt.internal.util.CustomLogManager
      .excludeMsg("Could not determine source for class ", Level.Warn),
  Compile / javacOptions --= Seq(
    "-source",
    frgaalSourceLevel,
    "--enable-preview"
  ),
  libraryDependencies ++= Seq(
    "org.graalvm.truffle" % "truffle-api"           % graalVersion % "provided",
    "org.graalvm.truffle" % "truffle-dsl-processor" % graalVersion % "provided"
  ),
  (Compile / javacOptions) ++= Seq(
    "-s",
    (Compile / sourceManaged).value.getAbsolutePath,
    "-Xlint:unchecked"
  )
)

lazy val `runtime-language-epb` =
  (project in file("engine/runtime-language-epb"))
    .settings(
      inConfig(Compile)(truffleRunOptionsSettings),
      instrumentationSettings
    )

lazy val runtime = (project in file("engine/runtime"))
  .configs(Benchmark)
  .settings(
    frgaalJavaCompilerSetting,
    Compile / logManager :=
      sbt.internal.util.CustomLogManager.excludeMsg(
        "Could not determine source for class ",
        Level.Warn
      ),
    version := ensoVersion,
    commands += WithDebugCommand.withDebug,
    inConfig(Compile)(truffleRunOptionsSettings),
    inConfig(Benchmark)(Defaults.testSettings),
    inConfig(Benchmark)(
      Defaults.compilersSetting
    ), // Compile benchmarks with javac, due to jmh issues
    Benchmark / javacOptions --= Seq(
      "-source",
      frgaalSourceLevel,
      "--enable-preview"
    ),
    Test / parallelExecution := false,
    Test / logBuffered := false,
    Test / testOptions += Tests.Argument(
      "-oD"
    ), // show timings for individual tests
    scalacOptions += "-Ymacro-annotations",
    scalacOptions ++= Seq("-Ypatmat-exhaust-depth", "off"),
    libraryDependencies ++= jmh ++ jaxb ++ circe ++ Seq(
      "com.ibm.icu"         % "icu4j"                 % icuVersion,
      "com.chuusai"        %% "shapeless"             % shapelessVersion,
      "org.apache.commons"  % "commons-lang3"         % commonsLangVersion,
      "org.apache.tika"     % "tika-core"             % tikaVersion,
      "org.graalvm.sdk"     % "graal-sdk"             % graalVersion      % "provided",
      "org.graalvm.sdk"     % "polyglot-tck"          % graalVersion      % "provided",
      "org.graalvm.truffle" % "truffle-api"           % graalVersion      % "provided",
      "org.graalvm.truffle" % "truffle-dsl-processor" % graalVersion      % "provided",
      "org.graalvm.truffle" % "truffle-tck"           % graalVersion      % "provided",
      "org.graalvm.truffle" % "truffle-tck-common"    % graalVersion      % "provided",
      "org.scalacheck"     %% "scalacheck"            % scalacheckVersion % Test,
      "org.scalactic"      %% "scalactic"             % scalacticVersion  % Test,
      "org.scalatest"      %% "scalatest"             % scalatestVersion  % Test,
      "org.graalvm.truffle" % "truffle-api"           % graalVersion      % Benchmark,
      "org.typelevel"      %% "cats-core"             % catsVersion,
      "junit"               % "junit"                 % junitVersion      % Test,
      "com.novocode"        % "junit-interface"       % "0.11"            % Test exclude ("junit", "junit-dep")
    ),
    Compile / compile / compileInputs := (Compile / compile / compileInputs)
      .dependsOn(CopyTruffleJAR.preCompileTask)
      .value,
    // Note [Classpath Separation]
    Test / javaOptions ++= Seq(
      "-Dgraalvm.locatorDisabled=true",
      s"--upgrade-module-path=${file("engine/runtime/build-cache/truffle-api.jar").absolutePath}"
    ),
    Test / fork := true,
    Test / envVars ++= distributionEnvironmentOverrides ++ Map(
      "ENSO_TEST_DISABLE_IR_CACHE" -> "false"
    ),
    bootstrap := CopyTruffleJAR.bootstrapJARs.value,
    Global / onLoad := EnvironmentCheck.addVersionCheck(
      graalVersion,
      javaVersion
    )((Global / onLoad).value)
  )
  .settings(
    (Compile / javacOptions) ++= Seq(
      "-s",
      (Compile / sourceManaged).value.getAbsolutePath,
      "-Xlint:unchecked"
    ),
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % kindProjectorVersion cross CrossVersion.full
    )
  )
  .settings(
    (Compile / compile) := (Compile / compile)
      .dependsOn(Def.task { (Compile / sourceManaged).value.mkdirs })
      .value
  )
  .settings(
    (Runtime / compile) := (Runtime / compile)
      .dependsOn(`std-base` / Compile / packageBin)
      .dependsOn(`std-image` / Compile / packageBin)
      .dependsOn(`std-database` / Compile / packageBin)
      .dependsOn(`std-google-api` / Compile / packageBin)
      .dependsOn(`std-table` / Compile / packageBin)
      .value
  )
  .settings(
    bench := (Benchmark / test).tag(Exclusive).value,
    benchOnly := Def.inputTaskDyn {
      import complete.Parsers.spaceDelimited
      val name = spaceDelimited("<name>").parsed match {
        case List(name) => name
        case _          => throw new IllegalArgumentException("Expected one argument.")
      }
      Def.task {
        (Benchmark / testOnly).toTask(" -- -z " + name).value
      }
    }.evaluated,
    Benchmark / parallelExecution := false
  )
  .dependsOn(`runtime-language-epb`)
  .dependsOn(`edition-updater`)
  .dependsOn(`interpreter-dsl`)
  .dependsOn(`library-manager`)
  .dependsOn(`logging-truffle-connector`)
  .dependsOn(`logging-utils`)
  .dependsOn(`polyglot-api`)
  .dependsOn(`text-buffer`)
  .dependsOn(graph)
  .dependsOn(pkg)
  .dependsOn(`edition-updater`)
  .dependsOn(`library-manager`)
  .dependsOn(`connected-lock-manager`)
  .dependsOn(syntax.jvm)
  .dependsOn(`docs-generator`)
  .dependsOn(testkit % Test)

lazy val `runtime-instrument-id-execution` =
  (project in file("engine/runtime-instrument-id-execution"))
    .settings(
      inConfig(Compile)(truffleRunOptionsSettings),
      instrumentationSettings
    )
    .dependsOn(runtime)

lazy val `runtime-instrument-repl-debugger` =
  (project in file("engine/runtime-instrument-repl-debugger"))
    .settings(
      inConfig(Compile)(truffleRunOptionsSettings),
      instrumentationSettings
    )
    .dependsOn(runtime)

lazy val `runtime-instrument-runtime-server` =
  (project in file("engine/runtime-instrument-runtime-server"))
    .settings(
      inConfig(Compile)(truffleRunOptionsSettings),
      instrumentationSettings
    )
    .dependsOn(runtime)

lazy val `runtime-with-instruments` =
  (project in file("engine/runtime-with-instruments"))
    .configs(Benchmark)
    .settings(
      frgaalJavaCompilerSetting,
      inConfig(Compile)(truffleRunOptionsSettings),
      inConfig(Benchmark)(Defaults.testSettings),
      commands += WithDebugCommand.withDebug,
      Benchmark / javacOptions --= Seq(
        "-source",
        frgaalSourceLevel,
        "--enable-preview"
      ),
      Test / javaOptions ++= Seq(
        "-Dgraalvm.locatorDisabled=true",
        s"--upgrade-module-path=${file("engine/runtime/build-cache/truffle-api.jar").absolutePath}"
      ),
      Test / fork := true,
      Test / envVars ++= distributionEnvironmentOverrides ++ Map(
        "ENSO_TEST_DISABLE_IR_CACHE" -> "false"
      ),
      libraryDependencies ++= Seq(
        "org.scalatest"      %% "scalatest"             % scalatestVersion % Test,
        "org.graalvm.truffle" % "truffle-api"           % graalVersion     % Test,
        "org.graalvm.truffle" % "truffle-dsl-processor" % graalVersion     % Test
      ),
      // Note [Unmanaged Classpath]
      Test / unmanagedClasspath += (baseDirectory.value / ".." / ".." / "app" / "gui" / "view" / "graph-editor" / "src" / "builtin" / "visualization" / "native" / "inc"),
      assembly / assemblyJarName := "runtime.jar",
      assembly / test := {},
      assembly / assemblyOutputPath := file("runtime.jar"),
      assembly / assemblyMergeStrategy := {
        case PathList("META-INF", file, xs @ _*) if file.endsWith(".DSA") =>
          MergeStrategy.discard
        case PathList("META-INF", file, xs @ _*) if file.endsWith(".SF") =>
          MergeStrategy.discard
        case PathList("META-INF", "MANIFEST.MF", xs @ _*) =>
          MergeStrategy.discard
        case PathList("META-INF", "services", xs @ _*) =>
          MergeStrategy.concat
        case _ => MergeStrategy.first
      }
    )
    .dependsOn(runtime % "compile->compile;test->test;runtime->runtime")
    .dependsOn(`runtime-instrument-id-execution`)
    .dependsOn(`runtime-instrument-repl-debugger`)
    .dependsOn(`runtime-instrument-runtime-server`)

/* Note [Unmanaged Classpath]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~
 * As the definition of the core primitives in `core_definition` is achieved
 * entirely using the graph macros, this means that the IDE experience for those
 * using these primitives is very poor.
 *
 * To get around this, we want to treat the core definition as a .jar dependency
 * to force the IDE to depend on bytecode for its diagnostics, rather than the
 * source code (as this means it sees the macros expanded). A standard workflow
 * with local publishing would not recompile the definition automatically on
 * changes, so the `unmanagedClasspath` route allows us to get automatic
 * recompilation but still convince the IDE that it is a .jar dependency.
 */

lazy val `engine-runner` = project
  .in(file("engine/runner"))
  .settings(
    javaOptions ++= {
      // Note [Classpath Separation]
      val runtimeClasspath =
        (runtime / Compile / fullClasspath).value
          .map(_.data)
          .mkString(File.pathSeparator)
      Seq(s"-Dtruffle.class.path.append=$runtimeClasspath")
    },
    Compile / run / mainClass := Some("org.enso.runner.Main"),
    assembly / mainClass := (Compile / run / mainClass).value,
    assembly / assemblyJarName := "runner.jar",
    assembly / test := {},
    assembly / assemblyOutputPath := file("runner.jar"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".DSA") =>
        MergeStrategy.discard
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".SF") =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF", xs @ _*) =>
        MergeStrategy.discard
      case "application.conf" =>
        MergeStrategy.concat
      case "reference.conf" =>
        MergeStrategy.concat
      case x =>
        MergeStrategy.first
    },
    commands += WithDebugCommand.withDebug,
    inConfig(Compile)(truffleRunOptionsSettings),
    libraryDependencies ++= Seq(
      "org.graalvm.sdk"     % "polyglot-tck" % graalVersion % "provided",
      "org.graalvm.truffle" % "truffle-api"  % graalVersion % "provided",
      "commons-cli"         % "commons-cli"  % commonsCliVersion,
      "com.monovore"       %% "decline"      % declineVersion,
      "org.jline"           % "jline"        % jlineVersion,
      "org.typelevel"      %% "cats-core"    % catsVersion
    ),
    run / connectInput := true
  )
  .settings(
    assembly := assembly
      .dependsOn(`runtime-with-instruments` / assembly)
      .value
  )
  .dependsOn(`version-output`)
  .dependsOn(pkg)
  .dependsOn(cli)
  .dependsOn(`library-manager`)
  .dependsOn(`language-server`)
  .dependsOn(`polyglot-api`)
  .dependsOn(`logging-service`)

lazy val launcher = project
  .in(file("engine/launcher"))
  .configs(Test)
  .settings(
    resolvers += Resolver.bintrayRepo("gn0s1s", "releases"),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging"    % scalaLoggingVersion,
      "org.typelevel"              %% "cats-core"        % catsVersion,
      "nl.gn0s1s"                  %% "bump"             % bumpVersion,
      "org.apache.commons"          % "commons-compress" % commonsCompressVersion,
      "org.scalatest"              %% "scalatest"        % scalatestVersion % Test,
      akkaSLF4J
    )
  )
  .settings(
    rebuildNativeImage := NativeImage
      .buildNativeImage(
        "enso",
        staticOnLinux = true,
        additionalOptions = Seq(
          "-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog",
          "-H:IncludeResources=.*Main.enso$"
        ),
        initializeAtRuntime = Seq(
          // Note [WSLoggerManager Shutdown Hook]
          "org.enso.loggingservice.WSLoggerManager$"
        )
      )
      .dependsOn(assembly)
      .dependsOn(VerifyReflectionSetup.run)
      .value,
    buildNativeImage := NativeImage
      .incrementalNativeImageBuild(
        rebuildNativeImage,
        "enso"
      )
      .value,
    assembly / test := {},
    assembly / assemblyOutputPath := file("launcher.jar"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".DSA") =>
        MergeStrategy.discard
      case PathList("META-INF", file, xs @ _*) if file.endsWith(".SF") =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF", xs @ _*) =>
        MergeStrategy.discard
      case "application.conf" => MergeStrategy.concat
      case "reference.conf"   => MergeStrategy.concat
      case x =>
        MergeStrategy.first
    }
  )
  .settings(
    (Test / test) := (Test / test)
      .dependsOn(buildNativeImage)
      .dependsOn(LauncherShimsForTest.prepare())
      .value,
    Test / parallelExecution := false
  )
  .dependsOn(cli)
  .dependsOn(`runtime-version-manager`)
  .dependsOn(`version-output`)
  .dependsOn(pkg)
  .dependsOn(`logging-service`)
  .dependsOn(`distribution-manager` % Test)
  .dependsOn(`runtime-version-manager-test` % Test)

lazy val `distribution-manager` = project
  .in(file("lib/scala/distribution-manager"))
  .configs(Test)
  .settings(
    resolvers += Resolver.bintrayRepo("gn0s1s", "releases"),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "io.circe"                   %% "circe-yaml"    % circeYamlVersion,
      "commons-io"                  % "commons-io"    % commonsIoVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )
  .dependsOn(editions)
  .dependsOn(cli)
  .dependsOn(pkg)
  .dependsOn(`logging-utils`)

lazy val editions = project
  .in(file("lib/scala/editions"))
  .configs(Test)
  .settings(
    resolvers += Resolver.bintrayRepo("gn0s1s", "releases"),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "nl.gn0s1s"                  %% "bump"          % bumpVersion,
      "io.circe"                   %% "circe-yaml"    % circeYamlVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )
  .settings(
    (Compile / compile) := (Compile / compile)
      .dependsOn(
        Def.task {
          Editions.writeEditionConfig(
            editionsRoot   = file("distribution") / "editions",
            ensoVersion    = ensoVersion,
            editionName    = currentEdition,
            libraryVersion = stdLibVersion,
            log            = streams.value.log
          )
        }
      )
      .value,
    cleanFiles += baseDirectory.value / ".." / ".." / "distribution" / "editions"
  )
  .dependsOn(testkit % Test)

lazy val downloader = (project in file("lib/scala/downloader"))
  .settings(
    version := "0.1",
    libraryDependencies ++= circe ++ Seq(
      "com.typesafe.scala-logging" %% "scala-logging"    % scalaLoggingVersion,
      "commons-io"                  % "commons-io"       % commonsIoVersion,
      "org.apache.commons"          % "commons-compress" % commonsCompressVersion,
      "org.scalatest"              %% "scalatest"        % scalatestVersion % Test,
      akkaActor,
      akkaStream,
      akkaHttp,
      akkaSLF4J
    )
  )
  .dependsOn(cli)

lazy val `edition-updater` = project
  .in(file("lib/scala/edition-updater"))
  .configs(Test)
  .settings(
    Test / test := (Test / test).tag(simpleLibraryServerTag).value,
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )
  .dependsOn(editions)
  .dependsOn(downloader)
  .dependsOn(`distribution-manager`)
  .dependsOn(`library-manager-test` % Test)

lazy val `edition-uploader` = project
  .in(file("lib/scala/edition-uploader"))
  .dependsOn(editions)
  .dependsOn(`version-output`)

lazy val `library-manager` = project
  .in(file("lib/scala/library-manager"))
  .configs(Test)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )
  .dependsOn(`version-output`) // Note [Default Editions]
  .dependsOn(editions)
  .dependsOn(cli)
  .dependsOn(`distribution-manager`)
  .dependsOn(downloader)
  .dependsOn(testkit % Test)
  .dependsOn(`logging-service` % Test)

lazy val `library-manager-test` = project
  .in(file("lib/scala/library-manager-test"))
  .configs(Test)
  .settings(
    Test / test := (Test / test).tag(simpleLibraryServerTag).value,
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion % Test
    )
  )
  .dependsOn(`library-manager`)
  .dependsOn(testkit)
  .dependsOn(`logging-service`)

lazy val `connected-lock-manager` = project
  .in(file("lib/scala/connected-lock-manager"))
  .configs(Test)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      akkaActor,
      akkaTestkit      % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test
    )
  )
  .dependsOn(`distribution-manager`)
  .dependsOn(`polyglot-api`)
  .dependsOn(testkit % Test)

lazy val `runtime-version-manager` = project
  .in(file("lib/scala/runtime-version-manager"))
  .configs(Test)
  .settings(
    resolvers += Resolver.bintrayRepo("gn0s1s", "releases"),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging"    % scalaLoggingVersion,
      "org.typelevel"              %% "cats-core"        % catsVersion,
      "nl.gn0s1s"                  %% "bump"             % bumpVersion,
      "org.apache.commons"          % "commons-compress" % commonsCompressVersion,
      "org.scalatest"              %% "scalatest"        % scalatestVersion % Test,
      akkaHttp
    )
  )
  .dependsOn(pkg)
  .dependsOn(downloader)
  .dependsOn(`logging-service`)
  .dependsOn(cli)
  .dependsOn(`version-output`)
  .dependsOn(`edition-updater`)
  .dependsOn(`distribution-manager`)

lazy val `runtime-version-manager-test` = project
  .in(file("lib/scala/runtime-version-manager-test"))
  .configs(Test)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "org.scalatest"              %% "scalatest"     % scalatestVersion,
      "commons-io"                  % "commons-io"    % commonsIoVersion
    )
  )
  .settings(Test / parallelExecution := false)
  .settings(
    (Test / test) := (Test / test)
      .dependsOn(`locking-test-helper` / assembly)
      .value
  )
  .dependsOn(`runtime-version-manager`)
  .dependsOn(`logging-service`)
  .dependsOn(testkit)
  .dependsOn(cli)
  .dependsOn(`distribution-manager`)

lazy val `locking-test-helper` = project
  .in(file("lib/scala/locking-test-helper"))
  .settings(
    assembly / test := {},
    assembly / assemblyOutputPath := file("locking-test-helper.jar")
  )

val `std-lib-root` = file("distribution/lib/Standard/")
def stdLibComponentRoot(name: String): File =
  `std-lib-root` / name / stdLibVersion
val `base-polyglot-root`  = stdLibComponentRoot("Base") / "polyglot" / "java"
val `table-polyglot-root` = stdLibComponentRoot("Table") / "polyglot" / "java"
val `image-polyglot-root` = stdLibComponentRoot("Image") / "polyglot" / "java"
val `google-api-polyglot-root` =
  stdLibComponentRoot("Google_Api") / "polyglot" / "java"
val `database-polyglot-root` =
  stdLibComponentRoot("Database") / "polyglot" / "java"

lazy val `std-base` = project
  .in(file("std-bits") / "base")
  .settings(
    frgaalJavaCompilerSetting,
    autoScalaLibrary := false,
    Compile / packageBin / artifactPath :=
      `base-polyglot-root` / "std-base.jar",
    libraryDependencies ++= Seq(
      "com.ibm.icu" % "icu4j" % icuVersion
    ),
    Compile / packageBin := Def.task {
      val result = (Compile / packageBin).value
      val _ = StdBits
        .copyDependencies(
          `base-polyglot-root`,
          Some("std-base.jar"),
          ignoreScalaLibrary = true
        )
        .value
      result
    }.value
  )

lazy val `std-table` = project
  .in(file("std-bits") / "table")
  .settings(
    frgaalJavaCompilerSetting,
    autoScalaLibrary := false,
    Compile / packageBin / artifactPath :=
      `table-polyglot-root` / "std-table.jar",
    libraryDependencies ++= Seq(
      "com.ibm.icu"         % "icu4j"             % icuVersion % "provided",
      "com.univocity"       % "univocity-parsers" % "2.9.1",
      "org.apache.poi"      % "poi-ooxml"         % "5.2.2",
      "org.apache.xmlbeans" % "xmlbeans"          % "5.1.0",
      "org.graalvm.truffle" % "truffle-api"       % graalVersion % "provided"
    ),
    Compile / packageBin := Def.task {
      val result = (Compile / packageBin).value
      val _ = StdBits
        .copyDependencies(
          `table-polyglot-root`,
          Some("std-table.jar"),
          ignoreScalaLibrary = true,
          unpackedDeps       = Set("xmlbeans")
        )
        .value
      result
    }.value
  )
  .dependsOn(`std-base` % "provided")

lazy val `std-image` = project
  .in(file("std-bits") / "image")
  .settings(
    frgaalJavaCompilerSetting,
    autoScalaLibrary := false,
    Compile / packageBin / artifactPath :=
      `image-polyglot-root` / "std-image.jar",
    libraryDependencies ++= Seq(
      "org.openpnp" % "opencv" % "4.5.1-2"
    ),
    Compile / packageBin := Def.task {
      val result = (Compile / packageBin).value
      val _ = StdBits
        .copyDependencies(
          `image-polyglot-root`,
          Some("std-image.jar"),
          ignoreScalaLibrary = true
        )
        .value
      result
    }.value
  )

lazy val `std-google-api` = project
  .in(file("std-bits") / "google-api")
  .settings(
    frgaalJavaCompilerSetting,
    autoScalaLibrary := false,
    Compile / packageBin / artifactPath :=
      `google-api-polyglot-root` / "std-google-api.jar",
    libraryDependencies ++= Seq(
      "com.google.api-client" % "google-api-client"          % "1.32.1",
      "com.google.apis"       % "google-api-services-sheets" % "v4-rev612-1.25.0"
    ),
    Compile / packageBin := Def.task {
      val result = (Compile / packageBin).value
      val _ = StdBits
        .copyDependencies(
          `google-api-polyglot-root`,
          Some("std-google-api.jar"),
          ignoreScalaLibrary = true
        )
        .value
      result
    }.value
  )

lazy val `std-database` = project
  .in(file("std-bits") / "database")
  .settings(
    frgaalJavaCompilerSetting,
    autoScalaLibrary := false,
    Compile / packageBin / artifactPath :=
      `database-polyglot-root` / "std-database.jar",
    libraryDependencies ++= Seq(
      "org.xerial"          % "sqlite-jdbc"           % "3.36.0.3",
      "org.postgresql"      % "postgresql"            % "42.4.0",
      "com.amazon.redshift" % "redshift-jdbc42"       % "2.1.0.9",
      "com.amazonaws"       % "aws-java-sdk-core"     % "1.12.273",
      "com.amazonaws"       % "aws-java-sdk-redshift" % "1.12.273",
      "com.amazonaws"       % "aws-java-sdk-sts"      % "1.12.273"
    ),
    Compile / packageBin := Def.task {
      val result = (Compile / packageBin).value
      val _ = StdBits
        .copyDependencies(
          `database-polyglot-root`,
          Some("std-database.jar"),
          ignoreScalaLibrary = true
        )
        .value
      result
    }.value
  )

/* Note [Native Image Workaround for GraalVM 20.2]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * In GraalVM 20.2 the Native Image build of even simple Scala programs has
 * started to fail on a call to `Statics.releaseFence`. It has been reported as
 * a bug in the GraalVM repository: https://github.com/oracle/graal/issues/2770
 *
 * A proposed workaround for this bug is to substitute the original function
 * with a different implementation that does not use the problematic
 * MethodHandle. This is implemented in class
 * `org.enso.launcher.workarounds.ReplacementStatics` using
 * `org.enso.launcher.workarounds.Unsafe` which gives access to
 * `sun.misc.Unsafe` which contains a low-level function corresponding to the
 * required "release fence".
 *
 * To allow for that substitution, the launcher code requires annotations from
 * the `svm` module and that is why this additional dependency is needed as long
 * as that workaround is in-place. The dependency is marked as "provided"
 * because it is included within the native-image build.
 */

/* Note [WSLoggerManager Shutdown Hook]
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * As the WSLoggerManager registers a shutdown hook when its initialized to
 * ensure that logs are not lost in case of logging service initialization
 * failure, it has to be initialized at runtime, as otherwise if the
 * initialization was done at build time, the shutdown hook would actually also
 * run at build time and have no effect at runtime.
 */

lazy val engineDistributionRoot =
  settingKey[File]("Root of built engine distribution")
lazy val launcherDistributionRoot =
  settingKey[File]("Root of built launcher distribution")
lazy val projectManagerDistributionRoot =
  settingKey[File]("Root of built project manager distribution")

engineDistributionRoot :=
  packageBuilder.localArtifact("engine") / s"enso-$ensoVersion"
launcherDistributionRoot := packageBuilder.localArtifact("launcher") / "enso"
projectManagerDistributionRoot :=
  packageBuilder.localArtifact("project-manager") / "enso"

lazy val buildEngineDistribution =
  taskKey[Unit]("Builds the engine distribution")
buildEngineDistribution := {
  val _ = (`engine-runner` / assembly).value
  updateLibraryManifests.value
  val root         = engineDistributionRoot.value
  val log          = streams.value.log
  val cacheFactory = streams.value.cacheStoreFactory
  DistributionPackage.createEnginePackage(
    distributionRoot    = root,
    cacheFactory        = cacheFactory,
    log                 = log,
    graalVersion        = graalVersion,
    javaVersion         = javaVersion,
    ensoVersion         = ensoVersion,
    editionName         = currentEdition,
    sourceStdlibVersion = stdLibVersion,
    targetStdlibVersion = targetStdlibVersion
  )
  log.info(s"Engine package created at $root")
}

val stdBitsProjects = List("Base", "Database", "Google_Api", "Image", "Table")
val allStdBits: Parser[String] =
  stdBitsProjects.map(v => v: Parser[String]).reduce(_ | _)

lazy val buildStdLib =
  inputKey[Unit]("Build an individual standard library package")
buildStdLib := Def.inputTaskDyn {
  val cmd: String = allStdBits.parsed
  val root: File  = engineDistributionRoot.value
  // Ensure that a complete distribution was built at least once.
  // Becasuse of `if` in the sbt task definition and usage of `streams.value` one has to
  // delegate to another task defintion (sbt restriction).
  if ((root / "manifest.yaml").exists) {
    pkgStdLibInternal.toTask(cmd)
  } else buildEngineDistribution
}.evaluated

lazy val pkgStdLibInternal = inputKey[Unit]("Use `buildStdLib`")
pkgStdLibInternal := Def.inputTaskDyn {
  val cmd             = allStdBits.parsed
  val root            = engineDistributionRoot.value
  val log: sbt.Logger = streams.value.log
  val cacheFactory    = streams.value.cacheStoreFactory
  cmd match {
    case "Base" =>
      (`std-base` / Compile / packageBin).value
    case "Database" =>
      (`std-database` / Compile / packageBin).value
    case "Google_Api" =>
      (`std-google-api` / Compile / packageBin).value
    case "Image" =>
      (`std-image` / Compile / packageBin).value
    case "Table" =>
      (`std-table` / Compile / packageBin).value
    case _ =>
  }
  StdBits.buildStdLibPackage(
    cmd,
    root,
    cacheFactory,
    log,
    defaultDevEnsoVersion
  )
}.evaluated

lazy val buildLauncherDistribution =
  taskKey[Unit]("Builds the launcher distribution")
buildLauncherDistribution := {
  val _            = (launcher / buildNativeImage).value
  val root         = launcherDistributionRoot.value
  val log          = streams.value.log
  val cacheFactory = streams.value.cacheStoreFactory
  DistributionPackage.createLauncherPackage(root, cacheFactory)
  log.info(s"Launcher package created at $root")
}

lazy val buildProjectManagerDistribution =
  taskKey[Unit]("Builds the project manager distribution")
buildProjectManagerDistribution := {
  val _            = (`project-manager` / buildNativeImage).value
  val root         = projectManagerDistributionRoot.value
  val log          = streams.value.log
  val cacheFactory = streams.value.cacheStoreFactory
  DistributionPackage.createProjectManagerPackage(root, cacheFactory)
  log.info(s"Project Manager package created at $root")
}

lazy val buildGraalDistribution =
  taskKey[Unit]("Builds the GraalVM distribution")
buildGraalDistribution := {
  val log    = streams.value.log
  val distOs = "DIST_OS"
  val osName = "os.name"
  val distName = sys.env.get(distOs).getOrElse {
    val name = sys.props(osName).takeWhile(!_.isWhitespace)
    if (sys.env.contains("CI")) {
      log.warn(
        s"$distOs env var is empty. Fallback to system property $osName=$name."
      )
    }
    name
  }
  val os = DistributionPackage.OS(distName).getOrElse {
    throw new RuntimeException(s"Failed to determine OS: $distName.")
  }
  packageBuilder.createGraalPackage(
    log,
    os,
    DistributionPackage.Architecture.X64
  )
}

lazy val updateLibraryManifests =
  taskKey[Unit](
    "Recomputes dependencies to update manifests bundled with libraries."
  )
updateLibraryManifests := {
  val _            = (`engine-runner` / assembly).value
  val log          = streams.value.log
  val cacheFactory = streams.value.cacheStoreFactory
  val libraries = Editions.standardLibraries.map(libName =>
    BundledLibrary(libName, stdLibVersion)
  )

  LibraryManifestGenerator.generateManifests(
    libraries,
    file("distribution"),
    log,
    cacheFactory
  )
}
