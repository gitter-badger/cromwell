package cromwell.engine

import java.nio.file.{Path, Paths}

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.FileAppender
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.engine.backend.jes.JesBackend
import cromwell.engine.backend.runtimeattributes.CromwellRuntimeAttributes
import cromwell.engine.backend.{Backend, BackendType, CromwellBackend}
import cromwell.engine.io.gcs.GoogleCloudStorage
import cromwell.engine.io.shared.SharedFileSystemIoInterface
import cromwell.engine.io.{IoInterface, IoManager}
import cromwell.engine.workflow.WorkflowOptions
import lenthall.config.ScalaConfig._
import org.slf4j.helpers.NOPLogger
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{JsObject, _}
import wdl4s._
import wdl4s.values.WdlFile

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scalaz.Scalaz._

case class WorkflowDescriptor(id: WorkflowId,
                              sourceFiles: WorkflowSourceFiles,
                              workflowOptions: WorkflowOptions,
                              rawInputs: Map[String, JsValue],
                              namespace: NamespaceWithWorkflow,
                              coercedInputs: WorkflowCoercedInputs,
                              declarations: WorkflowCoercedInputs,
                              backend: Backend,
                              configCallCaching: Boolean,
                              lookupDockerHash: Boolean,
                              gcsInterface: Try[GoogleCloudStorage],
                              ioManager: IoInterface,
                              wfContext: WorkflowContext,
                              engineFunctions: WorkflowEngineFunctions) {
  import WorkflowDescriptor._

  val shortId = id.toString.split("-")(0)
  val name = namespace.workflow.unqualifiedName
  val actualInputs: WorkflowCoercedInputs = coercedInputs ++ declarations
  val props = sys.props
  lazy val fileHasher: FileHasher = { wdlFile: WdlFile => SymbolHash(ioManager.hash(wdlFile.value)) }

  private lazy val optionCacheWriting = workflowOptions.getBoolean("write_to_cache") getOrElse configCallCaching
  private lazy val optionCacheReading = workflowOptions.getBoolean("read_from_cache") getOrElse configCallCaching

  if (!configCallCaching) {
    if (optionCacheWriting) logWriteDisabled()
    if (optionCacheReading) logReadDisabled()
  }

  lazy val writeToCache = configCallCaching && optionCacheWriting
  lazy val readFromCache = configCallCaching && optionCacheReading

  lazy val workflowLogger = props.get("LOG_MODE") match {
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

  private def logWriteDisabled() = workflowLogger.warn(writeDisabled)
  private def logReadDisabled() = workflowLogger.warn(readDisabled)
}

object WorkflowDescriptor {
  def apply(id: WorkflowId, sourceFiles: WorkflowSourceFiles): WorkflowDescriptor = {
    WorkflowDescriptor(id, sourceFiles, ConfigFactory.load)
  }

  def apply(id: WorkflowId, sourceFiles: WorkflowSourceFiles, conf: Config): WorkflowDescriptor = {
    validateWorkflowDescriptor(id, sourceFiles, CromwellBackend.backend(), conf) match {
      case scalaz.Success(w) => w
      case scalaz.Failure(f) =>
        throw new CromwellIllegalArgumentException(s"""Workflow $id failed to process inputs""", f.list.flatten)
    }
  }

  private def validateWorkflowDescriptor(id: WorkflowId,
                                         sourceFiles: WorkflowSourceFiles,
                                         backend: Backend,
                                         conf: Config): ErrorsOr[WorkflowDescriptor] = {
    val namespace = validateNamespace(id, sourceFiles.wdlSource)
    val rawInputs = validateRawInputs(id, sourceFiles.inputsJson)
    val options = validateWorkflowOptions(id, sourceFiles.workflowOptionsJson, backend)

    val runtimeAttributes = for {
      n <- namespace.disjunction
    } yield validateRuntimeAttributes(id, n, backend.backendType)

    (namespace |@| rawInputs |@| options |@| runtimeAttributes.validation) { (_, _, _, _) } match {
      case scalaz.Success((n, r, o, a)) => buildWorkflowDescriptor(id, sourceFiles, n, r, o, backend, conf)
      case scalaz.Failure(f) => scalaz.Failure(f)
    }
  }

  private def buildWorkflowDescriptor(id: WorkflowId,
                                      sourceFiles: WorkflowSourceFiles,
                                      namespace: NamespaceWithWorkflow,
                                      rawInputs: Map[String, JsValue],
                                      options: WorkflowOptions,
                                      backend: Backend,
                                      conf: Config): ErrorsOr[WorkflowDescriptor] = {
    val gcsInterface = GoogleCloudStorage.userAuthenticated(options) orElse GoogleCloudStorage.cromwellAuthenticated
    val ioManager = backend match {
      case _: JesBackend => gcsInterface getOrElse { // JesBackend only supports gcsInterface
        throw new Throwable("No GCS interface has been found. When running on JES Backend, Cromwell requires a google configuration to perform GCS operations.")
      }
      case _ => new IoManager(Seq(gcsInterface.toOption, Option(SharedFileSystemIoInterface.instance)).flatten)
    }
    val wfContext = backend.workflowContext(options, id, namespace.workflow.fullyQualifiedName)
    val engineFunctions = backend.engineFunctions(ioManager, wfContext)

    val validatedDescriptor = for {
      c <- validateCoercedInputs(id, rawInputs, namespace).disjunction
      d <- validateDeclarations(id, namespace, options, c, engineFunctions).disjunction
    } yield WorkflowDescriptor(id, sourceFiles, options, rawInputs, namespace, c, d, backend, configCallCaching(conf), lookupDockerHash(conf),
      gcsInterface, ioManager, wfContext, engineFunctions)
    validatedDescriptor.validation
  }

  private def validateNamespace(id: WorkflowId, source: WdlSource): ErrorsOr[NamespaceWithWorkflow] = {
    try {
      NamespaceWithWorkflow.load(source).successNel
    } catch {
      case e: Exception => List(s"Workflow $id unable to load namespace: ${e.getMessage}").failureNel
    }
  }

  private def validateRuntimeAttributes(id: WorkflowId, namespace: NamespaceWithWorkflow, backendType: BackendType): ErrorOr[Unit] = {
    Try(namespace.workflow.calls foreach { x => CromwellRuntimeAttributes(x.task.runtimeAttributes, backendType) }) match {
      case scala.util.Success(_) => ().successNel
      case scala.util.Failure(e) => s"Workflow $id contains bad runtime attributes: ${e.getMessage}".failureNel
    }
  }

  private def validateWorkflowOptions(id: WorkflowId,
                                      optionsJson: WorkflowOptionsJson,
                                      backend: Backend): ErrorsOr[WorkflowOptions] = {
    WorkflowOptions.fromJsonString(optionsJson) match {
      case Success(o) => validateBackendOptions(id, o, backend)
      case Failure(e) => List(s"Workflow $id contains bad options JSON: ${e.getMessage}").failureNel
    }
  }

  private def validateRawInputs(id: WorkflowId, json: WdlJson): ErrorsOr[Map[String, JsValue]] = {
    Try(json.parseJson) match {
      case Success(JsObject(inputs)) => inputs.successNel
      case _ => List(s"Workflow $id contains bad inputs JSON: $json").failureNel
    }
  }

  private def validateCoercedInputs(id: WorkflowId,
                                    rawInputs: Map[String, JsValue],
                                    namespace: NamespaceWithWorkflow): ErrorsOr[WorkflowCoercedInputs] = {
    namespace.coerceRawInputs(rawInputs) match {
      case Success(r) => r.successNel
      case Failure(e: UnsatisfiedInputsException) => e.getMessage.split("\n").toList.failureNel
      case Failure(e) => List(e.getMessage).failureNel
    }
  }

  private def validateBackendOptions(id: WorkflowId, options: WorkflowOptions, backend: Backend): ErrorsOr[WorkflowOptions] = {
    try {
      backend.assertWorkflowOptions(options)
      options.successNel
    } catch {
      case e: Exception =>
        List(s"Workflow $id has invalid options for backend ${backend.backendType}: ${e.getMessage}").failureNel
    }
  }

  private def validateDeclarations(id: WorkflowId,
                                   namespace: NamespaceWithWorkflow,
                                   options: WorkflowOptions,
                                   coercedInputs: WorkflowCoercedInputs,
                                   engineFunctions: WorkflowEngineFunctions): ErrorsOr[WorkflowCoercedInputs] = {
    namespace.staticDeclarationsRecursive(coercedInputs, engineFunctions) match {
      case Success(d) => d.successNel
      case Failure(e) => List(s"Workflow $id has invalid declarations: ${e.getMessage}").failureNel
    }
  }

  private val DefaultCallCachingValue = false
  private val DefaultLookupDockerHash = false

  private def disabledMessage(readWrite: String, consequence: String) =
    s"""$readWrite is enabled in the workflow options but Call Caching is disabled in this Cromwell instance.
       |As a result the calls in this workflow $consequence
       """.stripMargin

  private val writeDisabled = disabledMessage("Write to Cache", "WILL NOT be cached")
  private val readDisabled = disabledMessage("Read from Cache", "WILL ALL be executed")

  private def configCallCaching(conf: Config) = lookupBooleanWithDefault(conf, "call-caching", "enabled", DefaultCallCachingValue)
  private def lookupDockerHash(conf: Config) = lookupBooleanWithDefault(conf, "call-caching", "lookup-docker-hash", DefaultLookupDockerHash)

  private def lookupBooleanWithDefault(conf: Config, stanza: String, key: String, default: Boolean) = {
    (for {
      config <- conf.getConfigOption(stanza)
      value <- config.getBooleanOption(key)
    } yield value) getOrElse default
  }
}