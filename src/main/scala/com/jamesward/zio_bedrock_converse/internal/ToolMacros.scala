package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.BedrockConverse.{Tool, ToolName}
import zio.ZIO
import zio.schema.Schema

import scala.quoted.*

/**
 * Compile-time helpers behind the `.asTool` extension methods. Each
 * builds a [[Tool]] whose name is derived from the function reference
 * passed at the call site.
 *
 * Recognised function shapes for name extraction:
 *  - bare reference:           `getWeather`            (eta-expanded)
 *  - explicit eta-expansion:   `getWeather _`
 *  - lambda calling a method:  `(x: I) => getWeather(x)`
 *
 * For anonymous closures with no obvious method name the macro aborts
 * at compile time — callers should pass an explicit `ToolName` via
 * the regular `Tool` constructors instead.
 */
private[zio_bedrock_converse] object ToolMacros:

  def asToolPure[I: Type, O: Type](
    f:           Expr[I => O],
    description: Expr[String],
    schemaI:     Expr[Schema[I]],
    schemaO:     Expr[Schema[O]],
  )(using Quotes): Expr[Tool[I, O, Any, Nothing]] =
    val name = extractName(f)
    '{
      Tool.makePure[I, O](ToolName($name), $description, $f)(using $schemaI, $schemaO)
    }

  def asToolZIO[I: Type, O: Type, R: Type, E: Type](
    f:           Expr[I => ZIO[R, E, O]],
    description: Expr[String],
    schemaI:     Expr[Schema[I]],
    schemaO:     Expr[Schema[O]],
  )(using Quotes): Expr[Tool[I, O, R, E]] =
    val name = extractName(f)
    '{
      Tool.makeZIO[I, O, R, E](ToolName($name), $description, $f)(using $schemaI, $schemaO)
    }

  private def extractName(f: Expr[Any])(using Quotes): Expr[String] =
    import quotes.reflect.*

    def go(term: Term): Option[String] = term match
      case Inlined(_, _, inner)                         => go(inner)
      case Block(stats, expr)                           =>
        // Eta-expansion produces `Block(List(DefDef($anonfun, …)), Closure)`.
        // The real method name lives in the DefDef's body, so peek there
        // first, then fall back to the closure expression.
        val fromDefDef = stats.collectFirst:
          case d: DefDef => d.rhs.flatMap(go)
        .flatten
        fromDefDef.orElse(go(expr))
      case Closure(meth, _)                             => go(meth)
      case TypeApply(fun, _)                            => go(fun)
      case Apply(fun, _)                                => go(fun)
      case Select(_, name)                              => Some(name)
      case Ident(name) if !name.startsWith("$anonfun")  => Some(name)
      case _                                            => None

    go(f.asTerm) match
      case Some(name) => Expr(name)
      case None       => report.errorAndAbort(
        s"asTool: could not derive a tool name from the function — use the explicit-name constructor instead. (term: ${f.asTerm.show})"
      )
