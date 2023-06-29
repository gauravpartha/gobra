// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobra

import java.nio.file.Path
import ch.qos.logback.classic.Level
import org.bitbucket.inkytonik.kiama.util.Source
import org.scalatest.{Args, BeforeAndAfterAll, Status}
import viper.gobra.frontend.Parser.ParseManager
import viper.gobra.frontend.Source.FromFileSource
import viper.gobra.frontend.{Config, PackageInfo, PackageResolver, Source}
import viper.gobra.reporting.VerifierResult.{Failure, Success}
import viper.gobra.reporting.{NoopReporter, VerifierError}
import viper.silver.testing.{AbstractOutput, AnnotatedTestInput, ProjectInfo, SystemUnderTest}
import viper.silver.utility.TimingUtils
import viper.gobra.util.{DefaultGobraExecutionContext, GobraExecutionContext}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class GobraTests extends AbstractGobraTests with BeforeAndAfterAll {

  val regressionsPropertyName = "GOBRATESTS_REGRESSIONS_DIR"

  val regressionsDir: String = System.getProperty(regressionsPropertyName, "regressions")
  val testDirectories: Seq[String] = Vector(regressionsDir)
  override val defaultTestPattern: String = PackageResolver.inputFilePattern

  var gobraInstance: Gobra = _
  var executor: GobraExecutionContext = _
  var inputMapping: Vector[(PackageInfo, Vector[Source])] = Vector.empty
  // while we could pre-fetch and cache parse and maybe even type-check results, the regression test suite is designed
  // in a way that each file is its own test case. However, feeding a map of package infos to Gobra results in Gobra
  // considering these files in a Go way, i.e., groups them by package clause. This in turn results in testcase failures
  // as errors occur in files technically not under test but in the same directory and having the same package clause.
  val cacheParser = false

  override def beforeAll(): Unit = {
    executor = new DefaultGobraExecutionContext()
    gobraInstance = new Gobra()
  }

  override def registerTest(input: AnnotatedTestInput): Unit = {
    super.registerTest(input)
    val source = FromFileSource(input.file)
    inputMapping = inputMapping :+ (Source.getPackageInfo(source, Path.of("")) -> Vector(source))
  }

  override def runTests(testName: Option[String], args: Args): Status = {
    if (cacheParser) {
      val inputMap = inputMapping.toMap
      val config = Config(packageInfoInputMap = inputMap, cacheParser = true)
      val parseManager = new ParseManager(config, executor)
      parseManager.parseAll(inputMap.keys.toVector)
    }
    super.runTests(testName, args)
  }

  override def afterAll(): Unit = {
    executor.terminateAndAssertInexistanceOfTimeout()
    gobraInstance = null
  }

  val gobraInstanceUnderTest: SystemUnderTest =
    new SystemUnderTest with TimingUtils {
      /** For filtering test annotations. Does not need to be unique. */
      override val projectInfo: ProjectInfo = new ProjectInfo(List("Gobra"))

      override def run(input: AnnotatedTestInput): Seq[AbstractOutput] = {

        val source = FromFileSource(input.file)
        val packageInfoInputMap = if (cacheParser) inputMapping.toMap else Map(Source.getPackageInfo(source, Path.of("")) -> Vector(source))
        val config = Config(
          logLevel = Level.INFO,
          reporter = NoopReporter,
          packageInfoInputMap = packageInfoInputMap,
          checkConsistency = true,
          cacheParser = cacheParser,
          z3Exe = z3Exe
        )

        val pkgInfo = Source.getPackageInfo(source, Path.of(""))
        val (result, elapsedMilis) = time(() => Await.result(gobraInstance.verify(pkgInfo, config)(executor), Duration.Inf))

        info(s"Time required: $elapsedMilis ms")

        result match {
          case Success => Vector.empty
          case Failure(errors) => errors map GobraTestOuput
        }
      }
    }


  /**
    * The systems to test each input on.
    *
    * This method is not modeled as a constant field deliberately, such that
    * subclasses can instantiate a new [[viper.silver.testing.SystemUnderTest]]
    * for each test input.
    */
  override def systemsUnderTest: Seq[SystemUnderTest] = Vector(gobraInstanceUnderTest)

  case class GobraTestOuput(error: VerifierError) extends AbstractOutput {
    /** Whether the output belongs to the given line in the given file. */
    override def isSameLine(file: Path, lineNr: Int): Boolean = error.position.exists(_.line == lineNr)

    /** A short and unique identifier for this output. */
    override def fullId: String = error.id
  }
}
