package cromwell.engine.backend

import java.util.UUID

import akka.actor.ActorSystem
import cromwell.engine._
import cromwell.engine.backend.local.LocalBackend
import cromwell.engine.workflow.CallKey
import cromwell.util.SampleWdl
import cromwell.util.docker.SprayDockerRegistryApiClient
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class BackendCallSpec extends FlatSpec with Matchers with BeforeAndAfterAll with ScalaFutures with IntegrationPatience {
  val backend = new LocalBackend()
  val sources = SampleWdl.CallCachingHashingWdl.asWorkflowSources()
  val descriptor = WorkflowDescriptor(WorkflowId(UUID.randomUUID()), sources)
  val call = descriptor.namespace.workflow.calls.find(_.unqualifiedName == "t").get
  val actorSystem = ActorSystem("test-actor-system")
  val dockerRegistryApiClient = new SprayDockerRegistryApiClient()(actorSystem)
  val callKey = CallKey(call, None)
  val inputs = descriptor.actualInputs
  val abortRegistrationFunction = AbortRegistrationFunction(_ => ())
  val backendCall = backend.bindCall(descriptor, callKey, inputs, abortRegistrationFunction, dockerRegistryApiClient)

  "BackendCall hash function" should "not change very often" in {
    val actual = backendCall.hash(actorSystem.dispatcher).futureValue
    val expected = "8b6116bf5dad7be48066bb979b65b932"
    assert(actual == expected,  s"Expected BackendCall hash to be $expected, but got $actual.  Did the hashing algorithm change?")
  }

  override protected def afterAll() = actorSystem.shutdown()
}
