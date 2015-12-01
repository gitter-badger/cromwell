package cromwell.server

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import cromwell.engine.backend.Backend
import cromwell.engine.workflow.WorkflowManagerActor

trait WorkflowManagerSystem {
  protected def systemName = "cromwell-system"
  protected def newActorSystem(): ActorSystem = ActorSystem(systemName)
  implicit final val actorSystem = newActorSystem()
  lazy val backend: Backend = Backend.from(ConfigFactory.load.getConfig("backend"), actorSystem)
  lazy val backendType = backend.backendType
  // For now there's only one WorkflowManagerActor so no need to dynamically name it
  lazy val workflowManagerActor = actorSystem.actorOf(WorkflowManagerActor.props(backend), "WorkflowManagerActor")
}
