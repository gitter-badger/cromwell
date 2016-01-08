package cromwell.binding

class UnsatisfiedInputsException(message: String, cause: Throwable, val errors: List[String]) extends RuntimeException(message, cause) {
  def this(message: String) = this(message, null, List.empty)
  def this(message: String, errors: List[String]) = this(message, null, errors)
}
