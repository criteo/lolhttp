package lol.html

import fastparse.all._
import collection.mutable.ListBuffer

private[html] object Template {

  import scala.reflect.macros.blackbox.Context

  def macroImpl(c: Context)(args: c.Expr[Any]*): c.Expr[Html] = {
    import c.universe._

    val pos = c.enclosingPosition
    val startPoint =
      pos.point + (pos.source.content.drop(pos.point).take(7).mkString match {
        case "tmpl\"\"\"" => 7
        case _ => 5
      })

    def eval(literalString: Tree): String =
      literalString match {
        case Literal(Constant(value)) =>
          value.toString
      }

    c.prefix.tree match {
      case Apply(_, List(Apply(_, partTrees))) =>
        val ast = parse(partTrees.map(eval).mkString) match {
          case Parsed.Success(ast, _) =>
            ast
          case f @ Parsed.Failure(_, index, extra) =>
            c.abort(
              pos.withPoint(startPoint + index - 1),
              s"${extra.traced.expected} was expected here"
            )
        }
        val scalaSource = new StringBuffer
        val sourceMap = ListBuffer.empty[(Range,Int)]
        transpile(ast, scalaSource, sourceMap)

        def transposePoint(point: Int): Int =
          sourceMap.find(_._1.contains(point)).map {
            case (range, offset) =>
              startPoint + offset + (point - range.head)
          }.getOrElse(startPoint)

        def transpose(tree: Tree): Tree =
          atPos(
            tree.pos match {
              case NoPosition => pos
              case _ => pos.withPoint(transposePoint(tree.pos.point))
            }
          )(
            tree match {
              case Apply(fun, args) =>
                Apply(transpose(fun), args.map(transpose(_)))
              case Select(q, name) =>
                Select(transpose(q), name)
              case Ident(name) =>
                Ident(name)
              case Literal(value) =>
                Literal(value)
              case Function(params, body) =>
                Function(params, transpose(body))
              case If(cond, thenp, elsep) =>
                If(transpose(cond), transpose(thenp), transpose(elsep))
              case If(cond, thenp, elsep) =>
                If(transpose(cond), transpose(thenp), transpose(elsep))
              case Match(selector, cases) =>
                Match(transpose(selector), cases.map(transpose(_).asInstanceOf[CaseDef]))
              case CaseDef(pat, guard, body) =>
                CaseDef(transpose(pat), transpose(guard), transpose(body))
              case Bind(name, body) =>
                Bind(name, transpose(body))
              case EmptyTree =>
                EmptyTree
              case x =>
                sys.error(s"Missing case for ${x.getClass}")
            }
          )

        val tree =
          try {
            transpose(c.parse(scalaSource.toString))
          } catch {
            case e: scala.reflect.macros.ParseException =>
              c.abort(
                pos.withPoint(transposePoint(e.pos.point)),
                e.getMessage
              )
          }
        c.Expr(tree)
    }
  }

  sealed trait Ast
  case class Plain(html: String) extends Ast
  case class Expression(code: String, blocks: List[Block], position: Int) extends Ast
  case class Mixed(parts: List[Ast]) extends Ast
  case class Block(prefix: Option[String], args: Option[String], content: Mixed, position: Int) extends Ast
  case class MatchExpression(expr: String, cases: List[Case], position: Int) extends Ast
  case class Case(selector: String, content: Mixed, position: Int) extends Ast

  // Parse the template code to an AST
  def parse(code: String): Parsed[Mixed] = {
    lazy val ws: P0 =
      P(CharIn(" \t\n\r").rep)
    lazy val identifier: P[String] =
      P(CharPred(_.isLetter).! ~ (CharPred(_.isLetter) | CharPred(_.isDigit) | CharIn("-_")).rep.!)
        .map { case (x, xs) => x + xs }
        .opaque("<identifier>")
    lazy val parentheses: P[String] =
      P("(" ~/ (parentheses | (!(")") ~ AnyChar)).rep.! ~ ")")
        .map { case x => s"($x)" }
    lazy val brackets: P[String] =
      P("{" ~/ (brackets | (!("}") ~ AnyChar)).rep.! ~ "}")
        .map { case x => s"{$x}" }
    lazy val squareBrackets: P[String] =
      P("[" ~/ (squareBrackets | (!("]") ~ AnyChar)).rep.! ~ "]")
        .map { case x => s"[$x]" }
    lazy val simple: P[String] =
      P(identifier ~ squareBrackets.? ~ parentheses.rep)
        .map { case (x, mb, xs) => x + mb.getOrElse("") + xs.mkString }
    lazy val auto: P[String] =
      P(simple.rep(sep = ".", min = 1))
        .map { case code => code.mkString(".") }
    lazy val blockPrefix: P[String] =
      P(ws ~ (("else" ~ (" ".rep(min=1).! ~ "if" ~ parentheses).?) | "yield" | "match").!)
    lazy val blockArgs: P[String] =
      P(ws ~ (!("=>" | "\n") ~ AnyChar).rep(min = 1).! ~ "=>")
        .opaque("<block arguments>")
    lazy val block: P[Block] =
      P(Index ~ blockPrefix.? ~ ws ~ "{" ~ blockArgs.? ~ ws ~ mixed ~ ws ~ "}")
        .map { case (o, prefix, args, content) => Block(prefix, args, content, o)}
        .opaque("<block>")
    lazy val caseSelector: P[String] =
      P(ws ~ "case" ~ (!("=>" | "\n") ~ AnyChar).rep.! ~ "=>" ~ ws)
    lazy val caseBlock: P[Case] =
      P(Index ~ caseSelector ~ mixed)
        .map { case (o, selector, content) => Case(selector, content, o) }
        .opaque("<case>")
    lazy val matchCases: P[Seq[Case]] =
      P("match" ~ ws ~ "{" ~ caseBlock.rep ~ ws ~ "}")
    lazy val expression: P[Ast] =
      P( "@" ~/
        ( NoCut(Index ~ (parentheses | brackets)).map { case (o, code) => Expression(code, Nil, o) }
        | NoCut(Index ~ auto ~ " ".rep(min = 1) ~ matchCases).map { case (o, code, cases) => MatchExpression(code, cases.toList, o) }
        | NoCut(Index ~ auto ~ block.rep).map { case (o, code, blocks) => Expression(code, blocks.toList, o) }
        ).opaque("<scala expression>")
      )
    lazy val plain: P[Plain] =
      P((!("@" | "{" | ws ~ "}" | caseSelector) ~ AnyChar).rep(min = 1).!)
        .map { case html => Plain(html) }
    lazy val mixedBrackets: P[Mixed] =
      P("{" ~ mixed ~ ws.! ~ "}")
        .map { case (content, trailing) => Mixed(List(Plain("{"), content, Plain(trailing), Plain("}"))) }
    lazy val mixed: P[Mixed] =
      P((plain | expression | mixedBrackets).rep)
        .map { case parts => Mixed(parts.toList) }

    (Start ~ mixed ~ End).parse(code)
  }

  // Transpile the AST to scala code
  // It also generates a source map for nice error reporting
  def transpile(ast: Ast, buffer: StringBuffer, sourceMap: ListBuffer[(Range,Int)]): Unit =
    ast match {
      case Mixed(parts) =>
        parts.foreach {
          case part =>
            transpile(part, buffer, sourceMap)
            buffer.append(" ++ ")
        }
        buffer.append("_root_.lol.html.Html.empty")
      case Plain(html) =>
        buffer.append("_root_.lol.html.Html(\"\"\"")
        buffer.append(html)
        buffer.append("\"\"\")")
      case Expression(code, blocks, position) =>
        buffer.append(s"_root_.lol.html.toHtml(")
        sourceMap += (((buffer.length - 1) to (buffer.length - 1)) -> (position - 1))
        sourceMap += ((buffer.length to (buffer.length + code.length)) -> position)
        buffer.append(code)
        val autoElse =
          if(code.startsWith("if(") && blocks.collect { case Block(Some("else"), _, _, _) => () }.isEmpty)
            List(Block(Some("else"), None, Mixed(Nil), position))
          else Nil
        (blocks ++ autoElse).foreach(transpile(_, buffer, sourceMap))
        buffer.append(")")
      case Block(prefix, args, content, position) =>
        prefix.foreach { prefix =>
          buffer.append(" ")
          buffer.append(prefix)
        }
        buffer.append(" {")
        args.foreach { args =>
          sourceMap += ((buffer.length to (buffer.length + args.length)) -> position)
          buffer.append(args)
          buffer.append("=> ")
        }
        transpile(content, buffer, sourceMap)
        buffer.append("} ")
      case MatchExpression(expr, cases, position) =>
        sourceMap += ((buffer.length to (buffer.length + expr.length)) -> position)
        buffer.append("(")
        buffer.append(expr)
        buffer.append(" match {")
        cases.foreach(transpile(_, buffer, sourceMap))
        buffer.append("})")
      case Case(selector, content, position) =>
        buffer.append("case")
        sourceMap += ((buffer.length to (buffer.length + selector.length)) -> position)
        buffer.append(selector)
        buffer.append("=> {")
        transpile(content, buffer, sourceMap)
        buffer.append("} ")
    }

}