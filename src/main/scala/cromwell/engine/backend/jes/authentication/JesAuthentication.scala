package cromwell.engine.backend.jes.authentication

import cromwell.engine.WorkflowDescriptor
import cromwell.engine.backend.jes._
import cromwell.engine.io.gcs.GoogleCloudStorage
import cromwell.util.google.GoogleCredentialFactory
import spray.json.JsObject

/**
 * Trait for JesConnection
 */
trait JesConnection {
  def jesCromwellInterface: JesInterface

  /**
    * This method should try its best to provide a GCS connection setup with the user's credentials.
    * In the case where it's not able to provide such a method, a default one can be provided instead.
    */
  def jesUserConnection(workflow: WorkflowDescriptor): GoogleCloudStorage
}

object ProductionJesConnection {
  import ProductionJesConfiguration._

  // Only one instance of jesCromwellInterface is needed. It uses whichever authScheme has been set in the configuration.
  lazy val jesCromwellInterface: JesInterface = {
    val cromwellCredentials = GoogleCredentialFactory.fromCromwellAuthScheme
    // .get to fail now, as we can't run on Jes without a Cromwell authenticated GCS interface
    val gcsInterface = GoogleCloudStorage.cromwellAuthenticated.get
    val genomicsInterface = GenomicsFactory(googleConf.appName, jesConf.endpointUrl, cromwellCredentials)

    JesInterface(gcsInterface, genomicsInterface)
  }
}

/**
 * Trait for JesAuthentication
 */
trait JesAuthentication { self: JesConnection =>

  def authenticateAsCromwell[A](f: JesInterface => A) = f(jesCromwellInterface)

  /**
   * Important note: Will default back to cromwell authentication if the configuration for user authentication has not been set or if the refreshToken has been supplied.
   */
  def authenticateAsUser[A](workflow: WorkflowDescriptor)(f: GoogleCloudStorage => A) = f(jesUserConnection(workflow))

  /**
   * Generates a json containing auth information based on the parameters provided.
   * @return a string representation of the json
   */
  def generateAuthJson(authInformation: Option[JesAuthInformation]*) = {
    authInformation.flatten map { _.toMap } match {
      case Nil => None
      case jsons =>
        val authsValues = jsons.reduce(_ ++ _) mapValues JsObject.apply
        Option(JsObject("auths" -> JsObject(authsValues)).prettyPrint)
    }
  }
}

trait ProductionJesAuthentication extends JesAuthentication with JesConnection {
  override lazy val jesCromwellInterface = ProductionJesConnection.jesCromwellInterface

  /* We re-use the same workflow-level interface for all calls here.
   * We can .get safely because at WorkflowDescriptor instantiation time we make sure gcsInterface is defined if running on JES.
   * TODO: remove the Option[...] through inheritance or implicits ?
   */
  override def jesUserConnection(workflow: WorkflowDescriptor) = workflow.gcsInterface.get
}
