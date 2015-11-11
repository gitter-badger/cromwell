package cromwell.binding.expression

import cromwell.parser.WdlParser.AstNode

import scala.concurrent.{Future, ExecutionContext}
import scala.language.postfixOps
import scala.util.Try

trait Evaluator {
  type T
  type LookupFunction = String => Future[T]
  type Functions = WdlFunctions[T]
  def lookup: LookupFunction
  def functions: Functions
  def evaluate(ast: AstNode)(implicit ec: ExecutionContext): Future[T]
}

