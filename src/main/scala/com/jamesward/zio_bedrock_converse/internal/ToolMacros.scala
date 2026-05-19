package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock.{Tool, ToolName}
import zio.schema.Schema

import scala.quoted.*

/**
 * Compile-time helper behind the `.asTool` extension method.
 *
 * The handler-bearing `Tool` has been split out (Option β) — `Tool[I]`
 * now carries only the spec (name, description, `Schema[I]`). The macro
 * therefore discards the function body and keeps only its name.
 *
 * The tool name is derived from the function reference passed at the
 * call site. Recognised shapes:
 *  - bare reference:           `getWeather`            (eta-expanded)
 *  - explicit eta-expansion:   `getWeather _`
 *  - lambda calling a method:  `(x: I) => getWeather(x)`
 *
 * For anonymous closures with no obvious method name the macro aborts at
 * compile time — callers should pass an explicit `ToolName` via the
 * `Tool.apply` constructor instead.
 */
private[zio_bedrock_converse] object ToolMacros:

  def asTool[I: Type](
    f:           Expr[Any],
    description: Expr[String],
  )(using Quotes): Expr[Tool[I]] =
    val name    = extractName(f)
    val schemaI = summonSchema[I]
    '{ Tool[I](ToolName($name), $description)(using $schemaI) }

  private def summonSchema[T: Type](using Quotes): Expr[Schema[T]] =
    import quotes.reflect.*
    Expr.summon[Schema[T]].getOrElse:
      report.errorAndAbort(s"No `Schema[${Type.show[T]}]` in scope for `.asTool`.")

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
        s"asTool: could not derive a tool name from the function — use `Tool(ToolName(\"…\"), \"…\")` directly. (term: ${f.asTerm.show})"
      )
