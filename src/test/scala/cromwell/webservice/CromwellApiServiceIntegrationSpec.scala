package cromwell.webservice

import akka.testkit.TestActorRef
import cromwell.CromwellTestkitSpec.TestWorkflowManagerSystem
import cromwell.engine.backend.local.LocalBackend
import cromwell.engine.workflow.WorkflowManagerActor
import cromwell.util.SampleWdl.HelloWorld
import org.scalatest.{FlatSpec, Matchers}
import spray.http.{FormData, StatusCodes}
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.testkit.ScalatestRouteTest

class CromwellApiServiceIntegrationSpec extends FlatSpec with CromwellApiService with ScalatestRouteTest with Matchers {
  val testWorkflowManagerSystem = new TestWorkflowManagerSystem
  override def actorRefFactory = testWorkflowManagerSystem.actorSystem
  override val workflowManager = TestActorRef(new WorkflowManagerActor(new LocalBackend(actorRefFactory)))
  val version = "v1"

  it should "return 400 for a malformed WDL " in {
    Post(s"/workflows/$version", FormData(Seq("wdlSource" -> CromwellApiServiceSpec.MalformedWdl, "workflowInputs" -> HelloWorld.rawInputs.toJson.toString()))) ~>
      submitRoute ~>
      check {
        assertResult(StatusCodes.BadRequest) {
          status
        }
      }
  }
}
