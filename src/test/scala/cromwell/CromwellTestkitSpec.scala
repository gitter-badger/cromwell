package cromwell

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit._
import better.files.File
import com.typesafe.config.ConfigFactory
import cromwell.CromwellTestkitSpec._
import cromwell.engine.WorkflowOutputs
import wdl4s._
import wdl4s.values.{WdlArray, WdlFile, WdlString, WdlValue}
import cromwell.engine.ExecutionIndex.ExecutionIndex
import cromwell.engine._
import cromwell.engine.backend.CallLogs
import cromwell.engine.backend.local.LocalBackend
import cromwell.engine.workflow.WorkflowManagerActor
import cromwell.server.WorkflowManagerSystem
import cromwell.util.SampleWdl
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.matching.Regex

object CromwellTestkitSpec {
  val ConfigText =
    """
      |akka {
      |  loggers = ["akka.testkit.TestEventListener"]
      |  loglevel = "INFO"
      |  actor {
      |    debug {
      |       receive = on
      |    }
      |  }
      |  test {
      |    # Some of our tests fire off a message, then expect a particular event message within 3s (the default).
      |    # Especially on CI, the metadata test does not seem to be returning in time. So, overriding the timeouts
      |    # with slightly higher values. Alternatively, could also adjust the akka.test.timefactor only in CI.
      |    filter-leeway = 5s
      |    single-expect-default = 5s
      |    default-timeout = 10s
      |  }
      |}
    """.stripMargin

  val timeoutDuration = 30 seconds

  class TestWorkflowManagerSystem extends WorkflowManagerSystem {
    override protected def systemName: String = "test-system"
    override protected def newActorSystem() = ActorSystem(systemName, ConfigFactory.parseString(CromwellTestkitSpec.ConfigText))
    override val backendType = "local"
    backend // Force initialization
  }

  /**
   * Wait for exactly one occurrence of the specified info pattern in the specified block.  The block is in its own
   * parameter list for usage syntax reasons.
   */
  def waitForInfo[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.info(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Wait for occurrence(s) of the specified warning pattern in the specified block.  The block is in its own parameter
   * list for usage syntax reasons.
   */
  def waitForWarning[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.warning(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Wait for occurrence(s) of the specified error pattern in the specified block.  The block is in its own parameter
   * list for usage syntax reasons.
   */
  def waitForError[T](pattern: String, occurrences: Int = 1)(block: => T)(implicit system: ActorSystem): T = {
    EventFilter.error(pattern = pattern, occurrences = occurrences).intercept {
      block
    }
  }

  /**
   * Akka TestKit appears to be unable to match errors generated by `log.error(Throwable, String)` with the normal
   * `EventFilter.error(...).intercept {...}` mechanism since `EventFilter.error` forces the use of a dummy exception
   * that never matches a real exception.  This method works around that problem by building an `ErrorFilter` more
   * explicitly to allow the caller to specify a `Throwable` class.
   */
  def waitForErrorWithException[T](pattern: String,
                                   throwableClass: Class[_ <: Throwable] = classOf[Throwable],
                                   occurrences: Int = 1)
                                  (block: => T)
                                  (implicit system: ActorSystem): T = {
    val regex = Right[String, Regex](pattern.r)
    ErrorFilter(throwableClass, source = None, message = regex, complete = false)(occurrences = occurrences).intercept {
      block
    }
  }

  /**
    * Special case for validating outputs. Used when the test wants to check that an output exists, but doesn't care what
    * the actual value was.
    */
  lazy val AnyValueIsFine: WdlValue = WdlString("Today you are you! That is truer than true! There is no one alive who is you-er than you!")
}

abstract class CromwellTestkitSpec extends TestKit(new CromwellTestkitSpec.TestWorkflowManagerSystem().actorSystem)
with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with OneInstancePerTest {

  val name = this.getClass.getSimpleName
  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))

  def startingCallsFilter[T](callNames: String*)(block: => T): T =
    waitForPattern(s"starting calls: ${callNames.mkString(", ")}$$") {
      block
    }

  def waitForHandledMessage[T](named: String)(block: => T): T = {
    waitForHandledMessagePattern(s"^received handled message $named") {
      block
    }
  }

  def waitForHandledMessagePattern[T](pattern: String)(block: => T): T = {
    waitForInfo(pattern = pattern, occurrences = 1) {
      block
    }
  }

  /**
   * Performs the following steps:
   *
   * <ol>
   * <li> Sends the specified message to the implicitly passed `ActorRef` via an `ask`.
   * <li> Collects the `Future[Any]` response.
   * <li> Downcasts the `Future[Any]` to a `Future[M]`.
   * <li> Issues a blocking `Await.result` on the `Future`, yielding an `M`.
   * </ol>
   *
   */
  def messageAndWait[M: ClassTag](message: AnyRef)(implicit actorRef: ActorRef): M = {
    val futureAny = actorRef ? message
    Await.result(futureAny.mapTo[M], timeoutDuration)
  }

  /**
   * Wait for exactly one occurrence of the specified info pattern in the specified block within a limited amount of
   * time. The block is in its own parameter list for usage syntax reasons.
   */
  def waitForPattern[T](pattern: String, occurrences: Int = 1)(block: => T): T = {
    within(timeoutDuration) {
      waitForInfo(pattern, occurrences) {
        block
      }
    }
  }

  def buildWorkflowDescriptor(sampleWdl: SampleWdl, runtime: String): WorkflowDescriptor = {
    buildWorkflowDescriptor(sampleWdl, runtime, UUID.randomUUID())
  }

  def buildWorkflowDescriptor(sampleWdl: SampleWdl, runtime: String, uuid: UUID): WorkflowDescriptor = {
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    WorkflowDescriptor(WorkflowId(uuid), workflowSources)
  }

  private def buildWorkflowManagerActor(sampleWdl: SampleWdl, runtime: String) = {
    TestActorRef(new WorkflowManagerActor(new LocalBackend(system)))
  }

  // Not great, but this is so we can test matching data structures that have WdlFiles in them more easily
  private def validateOutput(output: WdlValue, expectedOutput: WdlValue): Unit = expectedOutput match {
    case expectedFile: WdlFile if output.isInstanceOf[WdlFile] =>
      val actualFile = output.asInstanceOf[WdlFile]
      actualFile.value.toString.endsWith(expectedFile.value.toString) shouldEqual true
    case expectedArray: WdlArray if output.isInstanceOf[WdlArray] =>
      val actualArray = output.asInstanceOf[WdlArray]
      actualArray.value.size should be(expectedArray.value.size)
      (actualArray.value zip expectedArray.value) foreach {
        case (actual, expected) => validateOutput(actual, expected)
      }
    case _ =>
      output shouldEqual expectedOutput
  }

  def runWdl(sampleWdl: SampleWdl,
             eventFilter: EventFilter,
             runtime: String = "",
             terminalState: WorkflowState = WorkflowSucceeded): WorkflowOutputs = {
    val wma = buildWorkflowManagerActor(sampleWdl, runtime)
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    var workflowId: WorkflowId = null
    eventFilter.intercept {
      within(timeoutDuration) {
        workflowId = Await.result(wma.ask(submitMessage).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        wma.ask(WorkflowManagerActor.WorkflowOutputs(workflowId)).mapTo[WorkflowOutputs].futureValue
      }
    }
  }

  def runWdlAndAssertOutputs(sampleWdl: SampleWdl,
                             eventFilter: EventFilter,
                             expectedOutputs: Map[FullyQualifiedName, WdlValue],
                             runtime: String = "",
                             workflowOptions: String = "{}",
                             allowOtherOutputs: Boolean = true,
                             terminalState: WorkflowState = WorkflowSucceeded): Unit = {
    val wma = buildWorkflowManagerActor(sampleWdl, runtime)
    val wfSources = sampleWdl.asWorkflowSources(runtime, workflowOptions)
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(wfSources)
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMessage).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        val outputs: WorkflowOutputs = wma.ask(WorkflowManagerActor.WorkflowOutputs(workflowId)).mapTo[WorkflowOutputs].futureValue

        val actualOutputNames = outputs.keys mkString ", "
        val expectedOutputNames = expectedOutputs.keys mkString " "

        expectedOutputs foreach { case (outputFqn, expectedValue) =>
          val actualValue = outputs.getOrElse(outputFqn, throw new RuntimeException(s"Expected output $outputFqn was not found in: '$actualOutputNames'"))
          if (expectedValue != AnyValueIsFine) validateOutput(actualValue.wdlValue, expectedValue)
        }
        if (!allowOtherOutputs) {
          outputs foreach { case (actualFqn, actualValue) =>
            val expectedValue = expectedOutputs.getOrElse(actualFqn, throw new RuntimeException(s"Actual output $actualFqn was not wanted in '$expectedOutputNames'"))
            if (expectedValue != AnyValueIsFine) validateOutput(actualValue.wdlValue, expectedValue)
          }
        }
      }
    }
  }

  /*
     FIXME: I renamed this as it appears to be asserting the stdout/stderr of a single call which is kinda weird for
     a full workflow type of thing
  */
  def runSingleCallWdlWithWorkflowManagerActor(wma: TestActorRef[WorkflowManagerActor],
                                               submitMsg: WorkflowManagerActor.SubmitWorkflow,
                                               eventFilter: EventFilter,
                                               fqn: FullyQualifiedName,
                                               index: ExecutionIndex,
                                               stdout: Option[Seq[String]],
                                               stderr: Option[Seq[String]],
                                               expectedOutputs: Map[FullyQualifiedName, WdlValue] = Map.empty ) = {
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMsg).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, WorkflowSucceeded)
        val standardStreams = Await.result(wma.ask(WorkflowManagerActor.CallStdoutStderr(workflowId, fqn)).mapTo[Seq[CallLogs]], timeoutDuration)
        stdout foreach { souts =>
          souts shouldEqual (standardStreams map { s => File(s.stdout.value).contentAsString })
        }
        stderr foreach { serrs =>
          serrs shouldEqual (standardStreams map { s => File(s.stderr.value).contentAsString })
        }
      }
    }
  }

  def runWdlWithWorkflowManagerActor(wma: TestActorRef[WorkflowManagerActor],
                                     submitMsg: WorkflowManagerActor.SubmitWorkflow,
                                     eventFilter: EventFilter,
                                     stdout: Map[FullyQualifiedName, Seq[String]],
                                     stderr: Map[FullyQualifiedName, Seq[String]],
                                     expectedOutputs: Map[FullyQualifiedName, WdlValue] = Map.empty,
                                     terminalState: WorkflowState = WorkflowSucceeded) = {
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMsg).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        val standardStreams = Await.result(wma.ask(WorkflowManagerActor.WorkflowStdoutStderr(workflowId)).mapTo[Map[FullyQualifiedName, Seq[CallLogs]]], timeoutDuration)

        stdout foreach {
          case(fqn, out) if standardStreams.contains(fqn) =>
          out shouldEqual (standardStreams(fqn) map { s => File(s.stdout.value).contentAsString })
        }
        stderr foreach {
          case(fqn, err) if standardStreams.contains(fqn) =>
          err shouldEqual (standardStreams(fqn) map { s => File(s.stderr.value).contentAsString })
        }
      }
    }
  }

  def runWdlAndAssertStdoutStderr(sampleWdl: SampleWdl,
                                  eventFilter: EventFilter,
                                  fqn: FullyQualifiedName,
                                  index: ExecutionIndex,
                                  runtime: String = "",
                                  stdout: Option[Seq[String]] = None,
                                  stderr: Option[Seq[String]] = None) = {
    val actor = buildWorkflowManagerActor(sampleWdl, runtime)
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    runSingleCallWdlWithWorkflowManagerActor(actor, submitMessage, eventFilter, fqn, index, stdout, stderr)
  }

  def runWdlAndAssertWorkflowStdoutStderr(sampleWdl: SampleWdl,
                                          eventFilter: EventFilter,
                                          runtime: String = "",
                                          stdout: Map[FullyQualifiedName, Seq[String]] = Map.empty[FullyQualifiedName, Seq[String]],
                                          stderr: Map[FullyQualifiedName, Seq[String]] = Map.empty[FullyQualifiedName, Seq[String]],
                                          terminalState: WorkflowState = WorkflowSucceeded) = {
    val actor = buildWorkflowManagerActor(sampleWdl, runtime)
    // TODO: these two lines seem to be duplicated a lot
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    runWdlWithWorkflowManagerActor(actor, submitMessage, eventFilter, stdout, stderr, Map.empty, terminalState)
  }

  private def verifyWorkflowState(wma: ActorRef, workflowId: WorkflowId, expectedState: WorkflowState): Unit = {
    // Continuously check the state of the workflow until it is in a terminal state
    awaitCond(pollWorkflowState(wma, workflowId).exists(_.isTerminal))
    // Now that it's complete verify that we ended up in the state we're expecting
    pollWorkflowState(wma, workflowId).get should equal (expectedState)
  }

  private def pollWorkflowState(wma: ActorRef, workflowId: WorkflowId): Option[WorkflowState] = {
    Await.result(wma.ask(WorkflowManagerActor.WorkflowStatus(workflowId)).mapTo[Option[WorkflowState]], timeoutDuration)
  }
}
