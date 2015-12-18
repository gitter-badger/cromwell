package cromwell.engine

import java.nio.file.{Path, Paths}

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.FileAppender
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.binding._
import cromwell.engine.backend.Backend
import cromwell.engine.workflow.WorkflowOptions
import cromwell.server.CromwellServer
import lenthall.config.ScalaConfig._
import org.slf4j.helpers.NOPLogger
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{JsObject, _}

import scala.util.{Failure, Success, Try}
import scalaz.Scalaz._
import scalaz.ValidationNel



/**
  * Constructs a representation of a particular workflow invocation.  As with other
  * case classes and apply() methods, this will throw an exception if it cannot be
  * created
  */
case class WorkflowDescriptor(id: WorkflowId,
                              sourceFiles: WorkflowSourceFiles,
                              workflowOptions: WorkflowOptions,
                              rawInputs: Map[String, JsValue],
                              namespace: NamespaceWithWorkflow,
                              coercedInputs: WorkflowCoercedInputs,
                              declarations: WorkflowCoercedInputs,
                              backend: Backend,
                              configCallCaching: Boolean) {
  import WorkflowDescriptor._

  val shortId = id.toString.split("-")(0)
  val ioInterface = backend.ioInterface(workflowOptions)
  val name = namespace.workflow.unqualifiedName
  val actualInputs: WorkflowCoercedInputs = coercedInputs ++ declarations
  val props = sys.props

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
    validateWorkflowDescriptor(id, sourceFiles, CromwellServer.backend, conf) match {
      case scalaz.Success(w) => w
      case scalaz.Failure(f) => throw new IllegalArgumentException(s"""\n${f.list.mkString("\n")}""")
    }
  }

  private def validateWorkflowDescriptor(id: WorkflowId,
                                         sourceFiles: WorkflowSourceFiles,
                                         backend: Backend,
                                         conf: Config): ValidationNel[String, WorkflowDescriptor] = {
    val namespace = validateNamespace(sourceFiles.wdlSource, backend)
    val rawInputs = validateRawInputs(id, sourceFiles.inputsJson)
    val options = validateWorkflowOptions(id, sourceFiles.workflowOptionsJson, backend)
    (namespace |@| rawInputs |@| options) { (_, _, _) } match {
      case scalaz.Success((n, r, o)) =>
        val validatedDescriptor = for {
          c <- validateCoercedInputs(id, r, n).disjunction
          d <- validateDeclarations(id, n, o, c, backend).disjunction
        } yield WorkflowDescriptor(id, sourceFiles, o, r, n, c, d, backend, configCallCaching(conf))
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
      case Failure(e) => s"Workflow ${id.toString} contains bad options JSON: ${e.getLocalizedMessage}".failureNel
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

  private val DefaultCallCachingValue = false

  private def disabledMessage(readWrite: String, consequence: String) =
    s"""$readWrite is enabled in the workflow options but Call Caching is disabled in this Cromwell instance.
       |As a result the calls in this workflow $consequence
       """.stripMargin

  val writeDisabled = disabledMessage("Write to Cache", "WILL NOT be cached")
  val readDisabled = disabledMessage("Read from Cache", "WILL ALL be executed")

  // TODO: Add to lenthall
  def getConfigOption(conf: Config, key: String): Option[Config] = if (conf.hasPath(key)) Option(conf.getConfig(key)) else None

  def configCallCaching(conf: Config): Boolean = {
    getConfigOption(conf, "call-caching") map { _.getBooleanOr("enabled", DefaultCallCachingValue) } getOrElse DefaultCallCachingValue
  }
}