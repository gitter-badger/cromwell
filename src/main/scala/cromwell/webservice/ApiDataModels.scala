package cromwell.webservice

import cromwell.engine.CromwellException
import cromwell.engine.backend.{CallLogs, CallMetadata, WorkflowQueryResult}
import org.joda.time.DateTime
import spray.json._
import wdl4s.FullyQualifiedName
import wdl4s.values.WdlValue

import scala.language.postfixOps

case class WorkflowValidateResponse(valid: Boolean, error: Option[String])

case class WorkflowStatusResponse(id: String, status: String)

case class WorkflowSubmitResponse(id: String, status: String)

case class WorkflowOutputResponse(id: String, outputs: Map[FullyQualifiedName, WdlValue])

case class WorkflowAbortResponse(id: String, status: String)

case class CallOutputResponse(id: String, callFqn: String, outputs: Map[FullyQualifiedName, WdlValue])

case class CallStdoutStderrResponse(id: String, logs: Map[String, Seq[CallLogs]])

case class WorkflowMetadataResponse(id: String,
                                    workflowName: String,
                                    status: String,
                                    submission: DateTime,
                                    start: Option[DateTime],
                                    end: Option[DateTime],
                                    inputs: JsObject,
                                    outputs: Option[Map[String, WdlValue]],
                                    calls: Map[String, Seq[CallMetadata]])

case class WorkflowQueryResponse(results: Seq[WorkflowQueryResult])

final case class CallCachingResponse(updateCount: Int)

object APIResponse {

  private def constructResponse(status: String, ex: Throwable) ={
    ex match {
      case cex: CromwellException => FailureResponse(status, cex.message, Option(JsArray(cex.errors.map(JsString(_)).toVector)))
      case e: Throwable => FailureResponse(status, e.getMessage, None)
    }
  }

  /**
    * When the data submitted in the request is incorrect.
    */
  def fail(ex: Throwable) = constructResponse("fail", ex)

  /**
    * When an exception was thrown during processing of the request
    */
  def error(ex: Throwable) = constructResponse("error", ex)
}

case class FailureResponse(status: String, message: String, errors: Option[JsValue] = None)
