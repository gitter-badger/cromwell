package cromwell.binding.command

import java.util.regex.Pattern

import cromwell.binding.{WdlSyntaxErrorFormatter, CallInputs, TaskInput}
import cromwell.binding.types.WdlArrayType
import cromwell.parser.WdlParser.{Ast, AstList, Terminal}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Try

object Command {
  def apply(ast: Ast, wdlSyntaxErrorFormatter: WdlSyntaxErrorFormatter): Command = {
    val parts = ast.getAttribute("parts").asInstanceOf[AstList].asScala.toVector.map {
      case x: Terminal => new StringCommandPart(x.getSourceString)
      case x: Ast => ParameterCommandPart(x, wdlSyntaxErrorFormatter)
    }

    new Command(parts)
  }
}

/**
 * Represents the `command` section of a `task` definition in a WDL file.
 *
 * A command is a sequence of CommandPart objects which can either be String
 * literals or parameters that the user needs to provide.  For example, consider
 * the following command:
 *
 * grep '${pattern}' ${file in}
 *
 * The Seq[CommandPart] would look as follows:
 *
 * <ol>
 * <li>StringCommandPart: <pre>grep '</pre></li>
 * <li>ParameterCommandPart: pattern (type=string)</li>
 * <li>StringCommandPart: <pre>' </pre></li>
 * <li>ParameterCommandPart: in (type=file)</li>
 * </ol>
 *
 * The result of the `inputs` method would be
 *
 * <ol>
 * <li>(<pre>pattern</pre>, <pre>string</pre>)</li>
 * <li>(<pre>in</pre>, <pre>file</pre>)</li>
 * </ol>
 *
 * A command line can be "instantiated" via the instantiate() method by providing a
 * value for all of its inputs.  The result is a string representation of the command
 *
 * @param parts The CommandParts that represent this command line
 */
case class Command(parts: Seq[CommandPart]) {
  val ws = Pattern.compile("[\\ \\t]+")
  def inputs: Seq[TaskInput] = {
    val collectedInputs = parts.collect {case p: ParameterCommandPart => p}.map {p =>
      // TODO: if postfix quantifier is + or *, then the type must be a primitive.
      val wdlType = p.postfixQuantifier match {
        case Some(x) if ParameterCommandPart.PostfixQuantifiersThatAcceptArrays.contains(x) => WdlArrayType(p.wdlType)
        case _ => p.wdlType
      }
      TaskInput(p.name, wdlType, p.postfixQuantifier)
    }

    /* It is assumed here that all TaskInputs with the same name are identically defined, this filters out duplicates by name */
    collectedInputs.map {_.name}.distinct.map {name =>
      collectedInputs.filter {_.name == name}.head
    }
  }

  /**
   * Given a map of task-local parameter names and WdlValues,
   * create a command String.
   *
   * Instantiating a command line is the process of taking a command in this form:
   *
   * {{{
   *   sh script.sh ${var1} -o ${var2}
   * }}}
   *
   * This command is stored as a `Seq[CommandPart]` in the `Command` class (e.g. [sh script.sh, ${var1}, -o, ${var2}]).
   * Then, given a map of variable -> value:
   *
   * {{{
   * {
   *   "var1": "foo",
   *   "var2": "bar"
   * }
   * }}}
   *
   * It calls instantiate() on each part, and passes this map. The ParameterCommandPart are the ${var1} and ${var2}
   * pieces and they lookup var1 and var2 in that map.
   *
   * The command that's returned from Command.instantiate() is:
   *
   *
   * {{{sh script.sh foo -o bar}}}
   *
   * @param parameters Parameter values
   * @return String instantiation of the command
   */
  def instantiate(parameters: CallInputs): Try[String] = {
    Try(normalize(parts.map { _.instantiate(parameters) }.mkString("")))
  }

  /**
   * 1) Remove all leading newline chars
   * 2) Remove all trailing newline AND whitespace chars
   * 3) Remove all *leading* whitespace that's common among every line in the input string
   *
   * For example, the input string:
   *
   * "
   *   first line
   *     second line
   *   third line
   *
   * "
   *
   * Would be normalized to:
   *
   * "first line
   *   second line
   * third line"
   *
   * @param s String to process
   * @return String which has common leading whitespace removed from each line
   */
  def normalize(s: String): String = {
    val trimmed = stripAll(s, "\r\n", "\r\n \t")
    val parts = trimmed.split("[\\r\\n]+")
    val indent = parts.map(leadingWhitespaceCount).min
    parts.map(_.substring(indent)).mkString("")
  }

  private def leadingWhitespaceCount(s: String): Int = {
    val matcher = ws.matcher(s)
    if (matcher.lookingAt) matcher.end else 0
  }

  private def stripAll(s: String, startChars: String, endChars: String): String = {
    /* https://stackoverflow.com/questions/17995260/trimming-strings-in-scala */
    @tailrec
    def start(n: Int): String = {
      if (n == s.length) ""
      else if (startChars.indexOf(s.charAt(n)) < 0) end(n, s.length)
      else start(1 + n)
    }
    @tailrec
    def end(a: Int, n: Int): String = {
      if (n <= a) s.substring(a, n)
      else if (endChars.indexOf(s.charAt(n - 1)) < 0) s.substring(a, n)
      else end(a, n - 1)
    }
    start(0)
  }

  override def toString: String = s"[Command: ${normalize(parts.map(y => y.toString).mkString(""))}]"
}