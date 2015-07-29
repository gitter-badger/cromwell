package cromwell.engine

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import cromwell.binding._
import cromwell.binding.values.WdlValue
import cromwell.engine
import cromwell.engine.backend.Backend
import cromwell.engine.workflow.WorkflowActor
import cromwell.engine.workflow.WorkflowActor.CallFailed

import scala.language.postfixOps
import scala.util.{Failure, Success}


object CallActor {
  sealed trait CallActorMessage
  case class Start(inputs: CallInputs) extends CallActorMessage
  case class ExecutionFinished(outputs: CallOutputs) extends CallActorMessage
  case class ExecutionFailed(regret: Throwable) extends CallActorMessage

  def props(call: Call, locallyQualifiedInputs: Map[String, WdlValue], backend: Backend, workflowDescriptor: WorkflowDescriptor): Props =
    Props(new CallActor(call, locallyQualifiedInputs, backend, workflowDescriptor))
}

/** Actor to manage the execution of a single call. */
class CallActor(call: Call, locallyQualifiedInputs: Map[String, WdlValue], backend: Backend, workflowDescriptor: WorkflowDescriptor) extends Actor with CromwellActor {

  type CallOutputs = Map[String, WdlValue]

  private val log = Logging(context.system, classOf[CallActor])
  val tag = s"CallActor [UUID(${workflowDescriptor.shortId}):${call.name}]"

  override def receive = LoggingReceive {
    case CallActor.Start => handleStart()
    case CallActor.ExecutionFinished(outputs) => context.parent ! WorkflowActor.CallCompleted(call, outputs)
    case CallActor.ExecutionFailed(regret) =>
      log.error(regret, s"$tag: ${regret.getMessage}")
      context.parent ! WorkflowActor.CallFailed(call, regret.getMessage)
    case badMessage =>
      val diagnostic = s"$tag: unexpected message $badMessage."
      log.error(diagnostic)
      context.parent ! engine.workflow.WorkflowActor.CallFailed(call, diagnostic)
  }

  /**
   * Performs the following steps:
   * <ol>
   *   <li>Instantiates the command line using these inputs.</li>
   *   <li>If the above completes successfully, messages the sender with `Started`,
   *   otherwise messages `Failed`.</li>
   *   <li>Executes the command with these inputs.</li>
   *   <li>Collects outputs in a `Try[Map[String, WdlValue]]`.</li>
   *   <li>If there are no `Failure`s among the outputs, messages the parent actor
   *   with `Completed`, otherwise messages `Failed`.</li>
   * </ol>
   */
  private def handleStart(): Unit = {
    // Record the original sender here and not in the yield over the `Future`, as the
    // value of sender() when that code executes may be different than what it is here.
    val originalSender = sender()
    val backendInputs = backend.adjustInputPaths(call, locallyQualifiedInputs)

    def failedInstantiation(e: Throwable): Unit = {
      val message = s"Call '${call.fullyQualifiedName}' failed to launch command: " + e.getMessage
      log.error(e, s"$tag: $message")
      context.parent ! CallFailed(call, message)
    }

    def launchCall(commandLine: String): Unit = {
      log.info(s"$tag: launching `$commandLine`")
      originalSender ! WorkflowActor.CallStarted(call)
      context.actorOf(CallExecutionActor.props(backend, commandLine, workflowDescriptor, call, backendInputs, inputName => locallyQualifiedInputs.get(inputName).get))
    }

    call.instantiateCommandLine(backendInputs) match {
      case Success(commandLine) => launchCall(commandLine)
      case Failure(e) => failedInstantiation(e)
    }
  }
}