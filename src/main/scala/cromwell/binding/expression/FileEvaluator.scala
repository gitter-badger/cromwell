package cromwell.binding.expression

import cromwell.binding.AstTools.EnhancedAstNode
import cromwell.binding.WdlExpression._
import cromwell.binding.WdlExpressionException
import cromwell.binding.types._
import cromwell.binding.values._
import cromwell.parser.WdlParser.{Ast, AstNode}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Success

class FileEvaluatorWdlFunctions extends WdlFunctions[Seq[WdlFile]]

/**
 * This evaluator will take a WdlExpression and determine all of the static files that
 * are referenced in this expression.
 *
 * This utilizes a ValueEvaluator to try to statically evaluate parts of the expression
 * into WdlValues.  See the evalValueToWdlFile() function.
 *
 * The coerceTo parameter is for the case where an output might be coerceable to a File.
 * Consider the following output section for a task:
 *
 * output {
 *   File a = "a.txt"
 *   String b = "b.txt"
 * }
 *
 * In the first case, the coerceTo would be WdlFileType and the calling evaluate("a.txt") would
 * return a Seq(WdlFile("a.txt")).  In the second case, coerceTo would be WdlStringType and
 * evaluate("b.txt") would return Seq().
 */
case class FileEvaluator(valueEvaluator: ValueEvaluator, coerceTo: WdlType = WdlAnyType) extends Evaluator {
  override type T = Seq[WdlFile]
  override def lookup = (s: String) => Future.successful(Seq.empty[WdlFile])
  override val functions = new FileEvaluatorWdlFunctions()

  private def evalValue(ast: AstNode)(implicit ec: ExecutionContext): Future[WdlValue] = valueEvaluator.evaluate(ast)

  private def evalValueToWdlFile(ast: AstNode)(implicit ec: ExecutionContext): Future[WdlFile] = {
    evalValue(ast) collect {
      case p: WdlPrimitive => WdlFile(p.valueString)
    } recover {
      case _ => throw new WdlExpressionException(s"Expecting a primitive type from AST:\n${ast.toPrettyString}")
    }
  }

  private def findWdlFiles(value: WdlValue, coerce: Boolean = true): Seq[WdlFile] = {
    val coercedValue = if (coerce) coerceTo.coerceRawValue(value) else Success(value)
    coercedValue match {
      case Success(f: WdlFile) => Seq(f)
      case Success(a: WdlArray) =>
        a.value.flatMap(findWdlFiles(_, coerce=false))
      case Success(m: WdlMap) =>
        m.value flatMap { case (k, v) => Seq(k, v) } flatMap(findWdlFiles(_, coerce=false)) toSeq
      case _ => Seq.empty[WdlFile]
    }
  }

  override def evaluate(ast: AstNode)(implicit ec: ExecutionContext): Future[Seq[WdlFile]] = {
    /**
     * First check if the top-level expression evaluates to a
     * literal value.  If it does, return all WdlFiles referenced
     * in that literal value
     */
    valueEvaluator.evaluate(ast) map {
      findWdlFiles(_)
    } recoverWith {
      case _ => evaluateRecursive(ast)
    }
  }

  /**
   * The pattern below is to try to evaluate all ASTs to a static value first, via
   * evalValueToWdlFile().  If this succeeds, return that value.  Otherwise, call
   * this function recursively.
   */
  private def evaluateRecursive(ast: AstNode)(implicit ec: ExecutionContext): Future[Seq[WdlFile]] = {
    ast match {
      case a: Ast if a.isGlobFunctionCall =>
        evalValueToWdlFile(a.params.head) map { wdlFile => Seq(WdlGlobFile(wdlFile.value)) }

      case a: Ast if a.isFunctionCallWithOneFileParameter =>
        evalValueToWdlFile(a.params.head) map {
          Seq(_)
        } recoverWith {
          case _ => evaluateRecursive(a.params.head)
        }
      case a: Ast if a.isBinaryOperator =>
        evalValueToWdlFile(a) flatMap {
          case f: WdlFile => Future.successful(Seq(f))
          case _ =>
            val concat = for {
              lhs <- evaluateRecursive(a.getAttribute("lhs"))
              rhs <- evaluateRecursive(a.getAttribute("rhs"))
            } yield lhs ++ rhs
            concat recover { throw new WdlExpressionException(s"Could not evaluate:\n${a.toPrettyString}") }
        }
      case a: Ast if a.isUnaryOperator =>
        evalValueToWdlFile(a) flatMap {
          case f: WdlFile => Future.successful(Seq(f))
          case _ =>
            evaluateRecursive(a.getAttribute("expression")) collect {
              case a: Seq[WdlFile] => a
            } recover {
              throw new WdlExpressionException(s"Could not evaluate:\n${a.toPrettyString}")
            }
        }
      case a: Ast if a.isArrayOrMapLookup =>
        evalValue(a) collect {
          case f: WdlFile => Seq(f)
        } recoverWith { case _ => evaluateRecursive(a.getAttribute("rhs")) }
      case a: Ast if a.isMemberAccess =>
        evalValue(a) collect {
          case f: WdlFile => Seq(f)
        } recover {
          case _ => Seq.empty[WdlFile]
        }
      case a: Ast if a.isArrayLiteral =>
        val values = a.getAttribute("values").astListAsVector().map(evaluateRecursive)
        // TODO don't understand what the original code was doing
        Future.sequence(values) map { _.flatten }
      case a: Ast if a.isMapLiteral =>
        val evaluatedMap = a.getAttribute("map").astListAsVector map { kv =>
          for {
            key <- evaluateRecursive(kv.asInstanceOf[Ast].getAttribute("key"))
            value <- evaluateRecursive(kv.asInstanceOf[Ast].getAttribute("value"))
          } yield key -> value
        }
        Future.sequence(evaluatedMap) map { _.flatten }
      case _ => Future.successful(Seq.empty[WdlFile])
    }
  }
}

