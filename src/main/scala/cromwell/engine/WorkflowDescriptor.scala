package cromwell.engine

import java.nio.file.{Path, Paths}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{LoggerContext, Level}
import ch.qos.logback.core.FileAppender
import cromwell.binding._
import cromwell.engine.backend.Backend
import cromwell.engine.workflow.WorkflowOptions
import cromwell.server.CromwellServer
import org.slf4j.{LoggerFactory, Logger}
import org.slf4j.helpers.NOPLogger
import spray.json._
import scala.util.{Try, Success, Failure}
import scalaz.Scalaz._
import scalaz._
import scalaz.ValidationNel

case class WorkflowDescriptor(id: WorkflowId,
                              sourceFiles: WorkflowSourceFiles,
                              workflowOptions: WorkflowOptions,
                              rawInputs: Map[String, JsValue],
                              namespace: NamespaceWithWorkflow,
                              coercedInputs: WorkflowCoercedInputs,
                              declarations: WorkflowCoercedInputs,
                              backend: Backend) {
  val shortId = id.toString.split("-")(0)
  val ioInterface = backend.ioInterface(workflowOptions)
  val name = namespace.workflow.name
  val actualInputs: WorkflowCoercedInputs = coercedInputs ++ declarations
  val props = sys.props

  val workflowLogger = props.get("LOG_MODE") match {
    case Some(x) if x.toUpperCase.contains("SERVER") => makeFileLogger(
      Paths.get(props.getOrElse("LOG_ROOT", ".")),
      s"workflow.$id.log",
      Level.toLevel(props.getOrElse("LOG_LEVEL", "debug"))
    )
    case _ => NOPLogger.NOP_LOGGER
  }

  private def makeFileLogger(root: Path, name: String, level: Level): Logger = {
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val encoder = new PatternLayoutEncoder()
    encoder.setPattern("%date %-5level - %msg%n")
    encoder.setContext(ctx)
    encoder.start()

    val path = root.resolve(name).toAbsolutePath.toString
    val appender = new FileAppender[ILoggingEvent]()
    appender.setFile(path)
    appender.setEncoder(encoder)
    appender.setName(name)
    appender.setContext(ctx)
    appender.start()

    val fileLogger = ctx.getLogger(name)
    fileLogger.addAppender(appender)
    fileLogger.setAdditive(false)
    fileLogger.setLevel(level)
    fileLogger
  }
}

object WorkflowDescriptor {
  def apply(id: WorkflowId, sourceFiles: WorkflowSourceFiles): WorkflowDescriptor = {
    validateWorkflowDescriptor(id, sourceFiles, CromwellServer.backend) match {
      case scalaz.Success(w) => w
      case scalaz.Failure(f) => throw new IllegalArgumentException(f.list.mkString(""))
    }
  }

  private def validateWorkflowDescriptor(id: WorkflowId,
                                         sourceFiles: WorkflowSourceFiles,
                                         backend: Backend): ValidationNel[String, WorkflowDescriptor] = {
    val namespace = validateNamespace(sourceFiles.wdlSource, backend)
    val rawInputs = validateRawInputs(id, sourceFiles.inputsJson)
    val options = validateWorkflowOptions(id, sourceFiles.workflowOptionsJson, backend)
    (namespace |@| rawInputs |@| options) { (_, _, _) } match {
      case scalaz.Success((n, r, o)) =>
        val validatedDescriptor = for {
          c <- validateCoercedInputs(id, r, n).disjunction
          d <- validateDeclarations(id, n, o, c, backend).disjunction
        } yield WorkflowDescriptor(id, sourceFiles, o, r, n, c, d, backend)
        validatedDescriptor.validation
      case scalaz.Failure(f) => f.list.mkString("").failureNel
    }
  }

  private def validateNamespace(source: WdlSource, backend: Backend): ValidationNel[String, NamespaceWithWorkflow] = {
    try {
      NamespaceWithWorkflow.load(source, backend.backendType).successNel
    } catch {
      case e: Exception => s"Workflow ${id.toString} unable to load namespace: ${e.getLocalizedMessage}".failureNel
    }
  }

  private def validateWorkflowOptions(id: WorkflowId,
                                      optionsJson: WorkflowOptionsJson,
                                      backend: Backend): ValidationNel[String, WorkflowOptions] = {
    WorkflowOptions.fromJsonString(optionsJson) match {
      case Success(o) =>
        // FIXME: Function-ify me
        val validatedOptions = o.successNel[String]
        val backendOptionsDisjunction = for {
          o <- validatedOptions.disjunction
          b <- validateBackendOptions(id, o, backend).disjunction
        } yield b
        val backendOptions = backendOptionsDisjunction.validation
        (validatedOptions |@| backendOptions) { (o, b) => o }
      case Failure(e) => s"Workflow ${id.toString} unable to process options: ${e.getLocalizedMessage}".failureNel
    }
  }

  private def validateRawInputs(id: WorkflowId, json: WdlJson): ValidationNel[String, Map[String, JsValue]] = {
    Try(json.parseJson) match {
      case Success(JsObject(inputs)) => inputs.successNel
      case _ => s"Workflow ${id.toString} contains bad inputs JSON: $json".failureNel
    }
  }

  private def validateCoercedInputs(id: WorkflowId,
                                    rawInputs: Map[String, JsValue],
                                    namespace: NamespaceWithWorkflow): ValidationNel[String, WorkflowCoercedInputs] = {
    namespace.coerceRawInputs(rawInputs) match {
      case Success(r) => r.successNel
      case Failure(e) => e.getLocalizedMessage.failureNel
    }
  }

  private def validateBackendOptions(id: WorkflowId, options: WorkflowOptions, backend: Backend): ValidationNel[String, Unit] = {
    try {
      backend.assertWorkflowOptions(options)
      ().successNel
    } catch {
      case e: Exception =>
        s"Workflow ${id.toString} has invalid options for backend ${backend.backendType}: ${e.getLocalizedMessage}".failureNel
    }
  }

  private def validateDeclarations(id: WorkflowId,
                                   namespace: NamespaceWithWorkflow,
                                   options: WorkflowOptions,
                                   coercedInputs: WorkflowCoercedInputs,
                                   backend: Backend): ValidationNel[String, WorkflowCoercedInputs] = {
    val ioInterface = backend.ioInterface(options)
    namespace.staticDeclarationsRecursive(coercedInputs, backend.engineFunctions(ioInterface)) match {
      case Success(d) => d.successNel
      case Failure(e) => s"Workflow ${id.toString} has invalid declarations: ${e.getLocalizedMessage}".failureNel
    }
  }
}
