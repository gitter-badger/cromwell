package cromwell.binding

import cromwell.engine.CromwellException

class UnsatisfiedInputsException(val message: String, cause: Throwable, val errors: List[String]) extends RuntimeException(message, cause) with CromwellException {
  def this(message: String) = this(message, null, List.empty)
  def this(message: String, errors: List[String]) = this(message, null, errors)
}
