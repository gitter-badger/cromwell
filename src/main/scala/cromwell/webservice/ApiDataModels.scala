package cromwell.webservice

import wdl4s.FullyQualifiedName
import wdl4s.values.WdlValue
import cromwell.engine.backend.{WorkflowQueryResult, CallMetadata, CallLogs}
import org.joda.time.DateTime
import spray.json.{JsValue, JsObject}

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

  private def sanitize(message: String) = message.replaceAll("\n", " - ")

  /**
    * When the data submitted in the request is incorrect.
    */
  def fail(message: String, data: Option[JsValue] = None) = {
    FailureResponse("fail", sanitize(message), data)
  }

  /**
    * When an exception was thrown during processing of the request
    */
  def error(message: String, data: Option[JsValue] = None) = {
    FailureResponse("error", sanitize(message), data)
  }
}

case class FailureResponse(status: String, message: String, data: Option[JsValue] = None)
