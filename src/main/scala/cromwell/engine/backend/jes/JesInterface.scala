package cromwell.engine.backend.jes

import java.net.URL

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.services.genomics.Genomics
import cromwell.engine.io.gcs.GoogleCloudStorage

case class JesInterface(storage: GoogleCloudStorage, genomics: Genomics)


object GenomicsFactory {
  def apply(appName: String, endpointUrl: URL, credential: Credential): Genomics = {
    GoogleGenomics.from(appName, endpointUrl, credential, credential.getJsonFactory, credential.getTransport)
  }

  // Wrapper object around Google's Genomics class providing a convenience 'from' "method"
  object GoogleGenomics {
    def from(applicationName: String, endpointUrl: URL, credential: Credential, jsonFactory: JsonFactory, httpTransport: HttpTransport): Genomics = {
      new Genomics.Builder(httpTransport, jsonFactory, credential).setApplicationName(applicationName).setRootUrl(endpointUrl.toString).build
    }
  }
}
