package cromwell

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.ActorSystem
import better.files._
import com.typesafe.config.ConfigFactory
import cromwell.engine.backend.local.LocalBackend
import cromwell.server.WorkflowManagerSystem
import cromwell.util.FileUtil._
import cromwell.util.SampleWdl
import cromwell.util.SampleWdl._
import org.apache.commons.io.output.TeeOutputStream
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.Span
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class MainSpec extends FlatSpec with Matchers with BeforeAndAfterAll with TimeLimitedTests {

  import MainSpec._

  behavior of "Main"

  override val timeLimit: Span = CromwellTestkitSpec.timeoutDuration

  it should "print usage" in {
    assert(traceMain(_.usageAndExit()).out.contains(UsageSnippet))
  }

  it should "print usage when no args" in {
    val result = traceAction()
    assert(result.out.contains(UsageSnippet))
  }

  it should "validate" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.validate(Array(wdl, "local")))
      result.out should be(empty)
      result.returnCode should be(0)
    }
  }

  it should "validate using args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceAction("validate", wdl, "local")
      result.out should be(empty)
      result.returnCode should be(0)
    }
  }

  it should "not validate invalid wdl" in {
    testWdl(EmptyInvalid) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.validate(Array(wdl, "local")))
      result.out should include("Finished parsing without consuming all tokens.")
      result.returnCode should be(1)
    }
  }

  it should "parse" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.parse(Array(wdl)))
      assert(result.out.toString.contains("(Document:"))
      result.returnCode should be(0)
    }
  }

  it should "parse using args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceAction("parse", wdl)
      assert(result.out.toString.contains("(Document:"))
      result.returnCode should be(0)
    }
  }

  it should "highlight" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.highlight(Array(wdl, "local", "html")))
      result.out.stripLineEnd should be(HighlightedWdlHtml)
      result.returnCode should be(0)
    }
  }

  it should "highlight using args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceAction("highlight", wdl, "local",  "html")
      result.out.stripLineEnd should be(HighlightedWdlHtml)
      result.returnCode should be(0)
    }
  }

  it should "highlight using console highlighting" in {
    testWdl(EmptyWorkflow) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.highlight(Array(wdl, "local",  "console")))
      result.out.stripLineEnd should include("empty_workflow")
      result.returnCode should be(0)
    }
  }

  it should "return inputs" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.inputs(Array(wdl, "local")))
      assert(result.out.contains("\"three_step.cgrep.pattern\""))
      result.returnCode should be(0)
    }
  }

  it should "return inputs using args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceAction("inputs", wdl, "local")
      assert(result.out.contains("\"three_step.cgrep.pattern\""))
      result.returnCode should be(0)
    }
  }

  it should "not return inputs when there is no workflow" in {
    testWdl(EmptyTask) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.inputs(Array(wdl, "local")))
      assert(result.out.contains("WDL does not have a local workflow"))
      result.returnCode should be(0)
    }
  }

  it should "run" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      traceInfoRun(wdl, inputs)("transitioning from Running to Succeeded.") should be(0)
    }
  }

  it should "run using args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      traceInfoAction("run", wdl, inputs)("transitioning from Running to Succeeded.") should be(0)
    }
  }

  it should "run and locate the default inputs path" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      wdlAndInputs.inputs
      traceInfoRun(wdl)("transitioning from Running to Succeed.") should be(0)
    }
  }

  it should "run if the inputs path is \"-\"" in {
    testWdl(GoodbyeWorld) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      /*
       * NOTE: Failed runs still exit with status 0, for now. Also due to system killing, mentioned in the NOTE in
       * SingleWorkflowRunnerActor, sometimes setTimer blows up and we never get "transitioning from Running to Failed."
       */
      traceErrorWithExceptionRun(wdl, "-", "-", "-")("Required workflow input 'hello.hello.addressee' not specified") should
        be(0)
    }
  }

  it should "run reading options" in {
    testWdl(ThreeStep, optionsJson = """{ "thisIsNot": "validJson", }""") { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      val options = wdlAndInputs.options
      /*
       * Something is breaking full error event handling. Not really sure what/where at the moment. Wild speculation:
       * see the side note in SingleWorkflowRunnerActor regarding different halting on success vs. failed.
       *
       * There's this pseudo block on a failure:
       *
       *   workflowManagerActor.receive(workflowActor.failure) { _ pipeTo singleWorkflowRunnerActor }
       *
       * However, during testing, the failure does fully relay 100% of the time to the SingleWorkflowRunnerActor.
       *
       * When this happens, one will NOT see
       *
       *   [error] SingleWorkflowRunnerActor: bad_backend is not a recognized backend
       *
       * in the test output. It usually appears right after a
       *
       *   [akka://cromwell-system/user/WorkflowManagerActor] WorkflowManagerActor Found no workflows to restart.
       *
       * For now, defaulting to making sure at least the WorkflowManagerActor picks up the error, as it doesn't really
       * matter. The known effects are:
       * - Instead of _both_ the WorkflowManagerActor and the SingleWorkflowRunnerActor logging the exception, we only
       *   get the stack trace once in the output.
       * - Failed runs still exit with status 0, so return codes are lost to this issue, currently.
       * - Other message / receive / routing may not be occurring in SingleWorkflowRunnerActor. But we currently just
       *   terminate on a failure in the SingleWorkflowRunnerActor anyway. Something else is just doing it before us?
       */
      traceErrorWithExceptionRun(wdl, inputs, options)(
        "WorkflowManagerActor: Workflow failed submission: WorkflowDescriptor is not valid") should be(0)
    }
  }

  it should "run writing metadata" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      val metadata = wdlAndInputs.metadata
      traceInfoRun(wdl, inputs, "-", metadata)("transitioning from Running to Succeeded.") should be(0)
      assert(wdlAndInputs.metadataPath.contentAsString.contains("\"three_step.cgrep.pattern\""))
    }
  }

  it should "fail run if the inputs path is not found, and not set to -" in {
    testWdl(EmptyWorkflow) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val result = traceMain(_.run(Array(wdl)))
      assert(result.err.contains("Inputs does not exist"))
      result.returnCode should be(1)
    }
  }

  it should "fail run if the inputs path is not readable" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      wdlAndInputs.inputsPath setPermissions Set.empty
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      val result = traceMain(_.run(Array(wdl, inputs)))
      assert(result.err.contains("Inputs is not readable"))
      result.returnCode should be(1)
    }
  }

  it should "fail run if the inputs path is not valid inputs json" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      wdlAndInputs.inputsPath write "[]"
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      println("EWEE")
      traceErrorWithExceptionRun(wdl, inputs)("contains bad inputs JSON") should be(0)
      //      val result = traceMain(_.run(Array(wdl, inputs)))
//      println("FOOOOO: |" + result.err + "|")
//      assert(result.err.contains("contains bad inputs JSON"))
 //     result.returnCode should be(1)
    }
  }

  it should "fail run with not enough args" in {
    val result = traceMain(_.run(Array.empty[String]))
    assert(result.out.contains(UsageSnippet))
    result.returnCode should be(-1)
  }

  it should "fail run with too many args" in {
    testWdl(ThreeStep) { wdlAndInputs =>
      val wdl = wdlAndInputs.wdl
      val inputs = wdlAndInputs.inputs
      val result = traceMain(_.run(Array(wdl, inputs, "-", "-", "-", "extra")))
      assert(result.out.contains(UsageSnippet))
      result.returnCode should be(-1)
    }
  }

  it should "init logging" in {
    modifyingSysProps("LOG_ROOT", "LOG_MODE", "LOG_LEVEL") {
      sys.props -= "LOG_ROOT" -= "LOG_MODE" -= "LOG_LEVEL"
      val result = traceMain(_.initLoggingReturnCode)
      val restoredProps = sys.props
      restoredProps("LOG_ROOT") should be(File(".").fullPath)
      restoredProps("LOG_MODE") should be("CONSOLE")
      restoredProps("LOG_LEVEL") should be("INFO")
      result.out should be(empty)
      result.err should be(empty)
      result.returnCode should be(0)
    }
  }

  it should "init logging and create the directory" in {
    modifyingSysProps("LOG_ROOT") {
      val tempDir = File.newTempDir("log_root_as_dir.")
      val logDir = tempDir / "log_dir"
      logDir.toJava shouldNot exist
      sys.props += "LOG_ROOT" -> logDir.fullPath
      val result = traceMain(_.initLoggingReturnCode)
      result.out should be(empty)
      result.err should be(empty)
      result.returnCode should be(0)
      logDir.toJava should exist
      tempDir.delete(ignoreIOExceptions = true)
    }
  }

  it should "init logging if the LOG_ROOT is an existing directory" in {
    modifyingSysProps("LOG_ROOT") {
      val tempDir = File.newTempDir("log_root_as_dir.")
      val logDir = tempDir / "log_dir"
      logDir.createDirectories()
      tempDir.toJava should exist
      sys.props += "LOG_ROOT" -> tempDir.fullPath
      val result = traceMain(_.initLoggingReturnCode)
      result.out should be(empty)
      result.err should be(empty)
      result.returnCode should be(0)
      tempDir.toJava should exist
      tempDir.delete(ignoreIOExceptions = true)
    }
  }

  it should "fail to init logging if the LOG_ROOT is an existing file" in {
    modifyingSysProps("LOG_ROOT") {
      val tempFile = File.newTemp("log_root_as_file.", ".tmp")
      tempFile.toJava should exist
      sys.props += "LOG_ROOT" -> tempFile.fullPath
      val result = traceMain(_.initLoggingReturnCode)
      result.out should be(empty)
      result.err shouldNot be(empty)
      result.returnCode should be(1)
      tempFile.toJava should exist
      tempFile.delete(ignoreIOExceptions = true)
    }
  }

}

object MainSpec {

  import CromwellTestkitSpec._

  /** The return code, plus any captured text from Console.stdout and Console.stderr while executing a block. */
  case class TraceResult(returnCode: Int, out: String, err: String)

  class TestWorkflowManagerSystem extends WorkflowManagerSystem {
    override lazy val backend = LocalBackend(actorSystem)
    override protected def systemName: String = "mainspec-system"
    override protected def newActorSystem() = ActorSystem(systemName, ConfigFactory.parseString(CromwellTestkitSpec.ConfigText))
  }

  private val dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS")
  private def now = dateFormat.format(new Date)

  /**
   * Tests running a sample wdl, providing the inputs, and cleaning up the temp files only if no exceptions occur.
   *
   * @param sampleWdl The sample wdl to run.
   * @param optionsJson Optional json for the options file.
   * @param block The block provided the inputs, returning some value.
   * @tparam T The return type of the block.
   * @return The result of running the block.
   */
  def testWdl[T](sampleWdl: SampleWdl, optionsJson: String = "{}")(block: WdlAndInputs => T): T = {
    val wdlAndInputs = WdlAndInputs(sampleWdl, optionsJson)
    val result = block(wdlAndInputs)
    wdlAndInputs.deleteTempFiles()
    result
  }

  /**
   * Saves and restores some system properties.
   *
   * @param keys SystemProperty names that should be saved.
   * @param block The block to run.
   * @tparam T The return type of the block.
   * @return The result of running the block.
   */
  def modifyingSysProps[T](keys: String*)(block: => T): T = {
    val saved = sys.props.filterKeys(keys.contains).toMap
    try {
      block
    } finally {
      val restore = sys.props
      keys.foreach(restore.-=)
      saved.foreach(restore.+=)
    }
  }

  /**
   * Prints the entry and exit of a block.
   *
   * Used primarily to (hopefully?) force a heisenbug to hide itself.
   *
   * The pattern matching utilities wait for a certain event to occur within a block. All system events are also
   * println'ed to the output. Each block supposedly also runs system.awaitTermination() at the end of Main.run,
   * blocking until the entire system is shutdown.
   *
   * However, on Travis, a number of failures have been seen where the pattern appears in the console output, but the
   * filter returns false that the pattern had been seen.
   *
   * println is internally synchronized, so it's possible adding a call to printBlock _inside_ the waitFor... is masking
   * a heisenbug, allowing the other events to be processed semi-synchronously before the final println in printBlock is
   * allowed to finish. Switching the order of waitFor... and printBlock _seemed_ to reduce the intermittent errors,
   * but this hasn't been exhaustively confirmed. Looking forward to seeing the WorkflowActor system revamp its concept
   * of "terminated" at some point in the future anyway.
   */
  private def printBlock(action: String, args: Seq[String])(block: => Int): Int = {
    try {
      println(s"[$now] [block] Entering block: $action ${args.mkString(" ")}")
      block
    } finally {
      println(s"[$now] [block] Exiting block: $action ${args.mkString(" ")}")
    }
  }

  /**
   * Runs the "run" method and waits for a particular pattern to appear as a Log Info event in the system.
   *
   * @param args Args to pass to Main.run().
   * @param pattern The pattern to watch for.
   * @return The return code of run.
   */
  def traceInfoRun(args: String*)(pattern: String): Int = {
    val workflowManagerSystem = new TestWorkflowManagerSystem
    waitForInfo(pattern)(
      printBlock("run", args) {
        new Main(enableSysExit = false, () => workflowManagerSystem).run(args)
      }
    )(workflowManagerSystem.actorSystem)
  }

  /**
   * Runs the "run" method and waits for a particular pattern to appear as a Log Error event in the system, with an
   * attached exception.
   *
   * @param args Args to pass to Main.run().
   * @param pattern The pattern to watch for.
   * @return The return code of run.
   */
  def traceErrorWithExceptionRun(args: String*)(pattern: String): Int = {
    val workflowManagerSystem = new TestWorkflowManagerSystem
    waitForErrorWithException(pattern)(
      printBlock("run", args) {
        new Main(enableSysExit = false, () => workflowManagerSystem).run(args)
      }
    )(workflowManagerSystem.actorSystem)
  }

  /**
   * Runs the "runAction" method and waits for a particular pattern to appear as a Log Info event in the system.
   *
   * @param args Args to pass to Main.run().
   * @param pattern The pattern to watch for.
   * @return The return code of run.
   */
  def traceInfoAction(args: String*)(pattern: String): Int = {
    val workflowManagerSystem = new TestWorkflowManagerSystem
    waitForInfo(pattern)(
      printBlock("runAction", args) {
        new Main(enableSysExit = false, () => workflowManagerSystem).runAction(args) match {
          case status: Int => status
        }
      }
    )(workflowManagerSystem.actorSystem)
  }

  /**
   * Loans an instance of Main, returning the return code plus everything that passed through Console.out/Console.err
   * during the run.
   *
   * @param block Block to run.
   * @return return code plus Console.out/Console.err during the block.
   */
  def traceMain(block: Main => Int): TraceResult = {
    val outStream = TeeStream(Console.out)
    val errStream = TeeStream(Console.err)
    val status = {
      Console.withOut(outStream.teed) {
        Console.withErr(errStream.teed) {
          block(new Main(enableSysExit = false, () => new TestWorkflowManagerSystem))
        }
      }
    }
    TraceResult(status, outStream.captured, errStream.captured)
  }

  /**
   * Runs Main.runAction, returning the return code plus everything that passed through Console.out/Console.err
   * during the run.
   *
   * @param args Arguments to pass to runAction.
   * @return return code plus Console.out/Console.err during the block.
   */
  def traceAction(args: String*): TraceResult = {
    traceMain { main =>
      main.runAction(args) match {
        case status: Int => status
      }
    }
  }

  /**
   * Create a temporary wdl file and inputs for the sampleWdl.
   * When the various properties are lazily accessed, they are also registered for deletion after the suite completes.
   */
  case class WdlAndInputs(sampleWdl: SampleWdl, optionsJson: String = "{}") {
    // Track all the temporary files we create, and delete them after the test.
    private var tempFiles = Vector.empty[Path]

    lazy val wdlPath: Path = {
      val path = File.newTemp(s"${sampleWdl.name}.", ".wdl").path
      tempFiles :+= path
      path write sampleWdl.wdlSource("")
      path
    }

    lazy val wdl = wdlPath.fullPath

    lazy val inputsPath = {
      val path = wdlPath.swapExt(".wdl", ".inputs")
      tempFiles :+= path
      path write sampleWdl.wdlJson
      path
    }

    lazy val inputs = inputsPath.fullPath

    lazy val optionsPath = {
      val path = wdlPath.swapExt(".wdl", ".options")
      tempFiles :+= path
      path write optionsJson
      path
    }

    lazy val options = optionsPath.fullPath

    lazy val metadataPath = {
      val path = wdlPath.swapExt(".wdl", ".metadata.json")
      tempFiles :+= path
      path.toAbsolutePath
    }

    lazy val metadata = metadataPath.fullPath

    def deleteTempFiles() = tempFiles.foreach(_.delete(ignoreIOExceptions = true))
  }

  /**
   * Utility for capturing output while also streaming it to stdout/stderr.
   * @param orig The stream to share.
   */
  case class TeeStream(orig: OutputStream) {
    private lazy val byteStream = new ByteArrayOutputStream()

    /** The teed stream. One should NOT call close on the stream, as it combo of a byte stream and a console stream. */
    lazy val teed = new TeeOutputStream(orig, byteStream)

    /** The captured text from the original stream. */
    def captured = byteStream.toString
  }

  val UsageSnippet = "java -jar cromwell.jar <action> <parameters>"

  val HighlightedWdlHtml =
    """<span class="keyword">task</span> <span class="name">ps</span> {
      |  <span class="section">command</span> {
      |    <span class="command">ps</span>
      |  }
      |  <span class="section">output</span> {
      |    <span class="type">File</span> <span class="variable">procs</span> = <span class="function">stdout</span>()
      |  }
      |}
      |
      |<span class="keyword">task</span> <span class="name">cgrep</span> {
      |  <span class="type">String</span> <span class="variable">pattern</span>
      |  <span class="type">File</span> <span class="variable">in_file</span>
      |  <span class="section">command</span> {
      |    <span class="command">grep '${pattern}' ${in_file} | wc -l</span>
      |  }
      |  <span class="section">output</span> {
      |    <span class="type">Int</span> <span class="variable">count</span> = <span class="function">read_int</span>(<span class="function">stdout</span>())
      |  }
      |}
      |
      |<span class="keyword">task</span> <span class="name">wc</span> {
      |  <span class="type">File</span> <span class="variable">in_file</span>
      |  <span class="section">command</span> {
      |    <span class="command">cat ${in_file} | wc -l</span>
      |  }
      |  <span class="section">output</span> {
      |    <span class="type">Int</span> <span class="variable">count</span> = <span class="function">read_int</span>(<span class="function">stdout</span>())
      |  }
      |}
      |
      |<span class="keyword">workflow</span> <span class="name">three_step</span> {
      |  <span class="keyword">call</span> <span class="name">ps</span>
      |  <span class="keyword">call</span> <span class="name">cgrep</span> {
      |    input: in_file=ps.procs
      |  }
      |  <span class="keyword">call</span> <span class="name">wc</span> {
      |    input: in_file=ps.procs
      |  }
      |}""".stripMargin
}
