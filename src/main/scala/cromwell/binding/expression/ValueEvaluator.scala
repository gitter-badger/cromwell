package cromwell.binding.expression

import cromwell.binding.AstTools.EnhancedAstNode
import cromwell.binding.WdlExpression._
import cromwell.binding.types._
import cromwell.binding.values.{WdlValue, _}
import cromwell.binding.{WdlExpressionException, WdlNamespace}
import cromwell.parser.WdlParser.{Ast, AstNode, Terminal}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class ValueEvaluator(override val lookup: ScopedLookupFunction, override val functions: WdlFunctions[WdlValue]) extends Evaluator {
  override type T = Future[WdlValue]

  private def replaceInterpolationTag(string: String, tag: String): Future[String] = {
    lookup(tag.substring(2, tag.length - 1)) map { lookupValue => string.replace(tag, lookupValue.valueString) }
  }

  private def interpolate(str: String): Future[String] = {

    def doInterpolate(currentString: String, matches: Seq[String]): Future[String] = {
      matches match {
        case m :: ms => replaceInterpolationTag(currentString, m) flatMap { newString => doInterpolate(newString, ms) }
        case Nil => Future.successful(currentString)
      }
    }

    val allMatches = "\\$\\{([a-zA-Z]([a-zA-Z0-9_])*)\\}".r.findAllIn(str).toSeq
    doInterpolate(str, allMatches)
  }

  override def evaluate(ast: AstNode): Future[WdlValue] = {
    ast match {
      case t: Terminal if t.getTerminalStr == "identifier" => lookup(t.getSourceString)
      case t: Terminal if t.getTerminalStr == "integer" => Future.successful(WdlInteger(t.getSourceString.toInt))
      case t: Terminal if t.getTerminalStr == "float" => Future.successful(WdlFloat(t.getSourceString.toDouble))
      case t: Terminal if t.getTerminalStr == "boolean" => Future.successful(WdlBoolean(t.getSourceString == "true"))
      case t: Terminal if t.getTerminalStr == "string" => interpolate(t.getSourceString) map WdlString
      case a: Ast if a.isBinaryOperator =>
        val lhs = evaluate(a.getAttribute("lhs"))
        val rhs = evaluate(a.getAttribute("rhs"))
        a.getName match {
          case "Add" => for(l <- lhs; r <- rhs) yield l.add(r).get
          case "Subtract" => for(l <- lhs; r <- rhs) yield l.subtract(r).get
          case "Multiply" => for(l <- lhs; r <- rhs) yield l.multiply(r).get
          case "Divide" => for(l <- lhs; r <- rhs) yield l.divide(r).get
          case "Remainder" => for(l <- lhs; r <- rhs) yield l.mod(r).get
          case "Equals" => for(l <- lhs; r <- rhs) yield l.equals(r).get
          case "NotEquals" => for(l <- lhs; r <- rhs) yield l.notEquals(r).get
          case "LessThan" => for(l <- lhs; r <- rhs) yield l.lessThan(r).get
          case "LessThanOrEqual" => for(l <- lhs; r <- rhs) yield l.lessThanOrEqual(r).get
          case "GreaterThan" => for(l <- lhs; r <- rhs) yield l.greaterThan(r).get
          case "GreaterThanOrEqual" => for(l <- lhs; r <- rhs) yield l.greaterThanOrEqual(r).get
          case "LogicalOr" => for(l <- lhs; r <- rhs) yield l.or(r).get
          case "LogicalAnd" => for(l <- lhs; r <- rhs) yield l.and(r).get
          case _ => Future.failed(new WdlExpressionException(s"Invalid operator: ${a.getName}"))
        }
      case a: Ast if a.isUnaryOperator =>
        val expression = evaluate(a.getAttribute("expression"))
        a.getName match {
          case "LogicalNot" => for(e <- expression) yield e.not.get
          case "UnaryPlus" => for(e <- expression) yield e.unaryPlus.get
          case "UnaryNegation" => for(e <- expression) yield e.unaryMinus.get
          case _ => Future.failed(new WdlExpressionException(s"Invalid operator: ${a.getName}"))
        }
      case a: Ast if a.isArrayLiteral =>
        val evaluatedElements = a.getAttribute("values").astListAsVector map evaluate
        for {
          elements <- Future.sequence(evaluatedElements)
          subtype <- WdlType.homogeneousTypeFromValues(elements)
        } yield WdlArray(WdlArrayType(subtype), elements)
      case a: Ast if a.isMapLiteral =>
        // TODO This may need some rework to get the same Validation-like accumulation of Failures as the
        // TODO synchronous original if that's important.
        val seqOfFutureWdlValuePairs = a.getAttribute("map").astListAsVector map { kv =>
          for {
            key <- evaluate(kv.asInstanceOf[Ast].getAttribute("key")) recover { case t => throw new WdlExpressionException(s"Could not evaluate expression:\n$t") }
            value <- evaluate(kv.asInstanceOf[Ast].getAttribute("value")) recover { case t => throw new WdlExpressionException(s"Could not evaluate expression:\n$t") }
          } yield key -> value
        }
        for {
          mapOfWdlValues <- Future.sequence(seqOfFutureWdlValuePairs)
          wdlMap <- Future.fromTry(WdlMapType(WdlAnyType, WdlAnyType).coerceRawValue(mapOfWdlValues.toMap))
        } yield wdlMap
      case a: Ast if a.isMemberAccess =>
        a.getAttribute("rhs") match {
          case rhs: Terminal if rhs.getTerminalStr == "identifier" =>
            evaluate(a.getAttribute("lhs")).flatMap {
              case o: WdlObjectLike =>
                o.value.get(rhs.getSourceString) match {
                  case Some(v: WdlValue) => Future.successful(v)
                  case None => Future.failed(new WdlExpressionException(s"Could not find key ${rhs.getSourceString}"))
                }
              case a: WdlArray if a.wdlType == WdlArrayType(WdlObjectType) =>
                /**
                 * This case is for slicing an Array[Object], used mainly for scatter-gather.
                 * For example, if 'call foo' was in a scatter block, foo's outputs (e.g. Int x)
                 * would be an Array[Int].  If a downstream call has an input expression "foo.x",
                 * then 'foo' would evaluate to an Array[Objects] and foo.x would result in an
                 * Array[Int]
                 */
                Future.successful(a map {_.asInstanceOf[WdlObject].value.get(rhs.sourceString).get})
              case ns: WdlNamespace => lookup(ns.importedAs.map {n => s"$n.${rhs.getSourceString}"}.getOrElse(rhs.getSourceString))
              case _ => Future.failed(new WdlExpressionException("Left-hand side of expression must be a WdlObject or Namespace"))
            }
          case _ => Future.failed(new WdlExpressionException("Right-hand side of expression must be identifier"))
        }
      case a: Ast if a.isArrayOrMapLookup =>
        val pair = for {
          index <- evaluate(a.getAttribute("rhs"))
          mapOrArray <- evaluate(a.getAttribute("lhs"))
        } yield (mapOrArray, index)
        pair map {
          case (a: WdlArray, i: WdlInteger) =>
            Try(a.value(i.value)) match {
              case Success(s) => s
              case Failure(ex) => throw new WdlExpressionException(s"Failed to find index $i on array:\n\n$a\n\n${ex.getMessage}")
            }
          case (m: WdlMap, v: WdlValue) =>
            m.value.get(v) match {
              case Some(value) => value
              case _ => throw new WdlExpressionException(s"Failed to find a key '$v' on a map:\n\n$m")
            }
          case (mapOrArray, index) => throw new WdlExpressionException(s"Can't index $mapOrArray with index $index")
        }
      case a: Ast if a.isFunctionCall =>
        val name = a.getAttribute("name").sourceString
        val params = a.params map evaluate
        functions.getFunction(name)(params)
    }
  }
}

