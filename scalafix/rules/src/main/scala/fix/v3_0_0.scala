package fix

import scalafix.v1._

import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  /*
  TODO:
   - not found: type Timer
   - not found: type ConcurrentEffect
   - not found: value Blocker
   */

  override def fix(implicit doc: SemanticDocument): Patch = {
    val Bracket_guarantee_M = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")
    val Bracket_uncancelable_M = SymbolMatcher.exact("cats/effect/Bracket#uncancelable().")
    val ContextShift_M = SymbolMatcher.normalized("cats/effect/ContextShift.")

    Patch.replaceSymbols(
      "cats/effect/Async#async()." -> "async_",
      "cats/effect/package.BracketThrow." -> "cats/effect/MonadCancelThrow.",
      "cats/effect/Bracket." -> "cats/effect/MonadCancel.",
      "cats/effect/IO.async()." -> "async_",
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/ResourceLike#parZip()." -> "both",
      "cats/effect/Resource.liftF()." -> "eval",
      "cats/effect/concurrent/Deferred." -> "cats/effect/Deferred.",
      "cats/effect/concurrent/Ref." -> "cats/effect/Ref.",
      "cats/effect/concurrent/Semaphore." -> "cats/effect/std/Semaphore."
    ) +
      doc.tree.collect {
        // Bracket#guarantee(a)(b) -> MonadCancel#guarantee(a, b)
        case t @ q"${Bracket_guarantee_M(_)}($a)($b)" =>
          fuseParameterLists(t, a, b)

        // Bracket#uncancelable(a) -> MonadCancel#uncancelable(_ => a)
        case q"${Bracket_uncancelable_M(_)}($a)" =>
          Patch.addLeft(a, "_ => ")

        case t @ ImporteeNameOrRename(ContextShift_M(_)) =>
          Patch.removeImportee(t)

        case d: Defn.Def =>
          removeParam(d, ContextShift_M)
      }.asPatch
  }

  object ImporteeNameOrRename {
    def unapply(importee: Importee): Option[Name] =
      importee match {
        case Importee.Name(x)      => Some(x)
        case Importee.Rename(x, _) => Some(x)
        case _                     => None
      }
  }

  // tree @ f(param1)(param2) -> f(param1, param2)
  private def fuseParameterLists(tree: Tree, param1: Tree, param2: Tree): Patch =
    (param1.tokens.lastOption, param2.tokens.headOption) match {
      case (Some(lastA), Some(firstB)) =>
        val between =
          tree.tokens.dropWhile(_ != lastA).drop(1).dropRightWhile(_ != firstB).dropRight(1)
        val maybeParen1 = between.find(_.is[Token.RightParen])
        val maybeParen2 = between.reverseIterator.find(_.is[Token.LeftParen])
        (maybeParen1, maybeParen2) match {
          case (Some(p1), Some(p2)) =>
            val toAdd = if (lastA.end == p1.start && p1.end == p2.start) ", " else ","
            Patch.replaceToken(p1, toAdd) + Patch.removeToken(p2)
          case _ => Patch.empty
        }
      case _ => Patch.empty
    }

  // f(p1, symbolMatcher(p2), p3) -> f(p1, p3)
  private def removeParam(d: Defn.Def, symbolMatcher: SymbolMatcher)(implicit
      doc: SemanticDocument
  ): Patch = {
    d.paramss.find(_.exists(_.decltpe.exists(symbolMatcher.matches))) match {
      case None => Patch.empty
      case Some(params) =>
        params match {
          // There is only one parameter, so we're removing the complete parameter list.
          case param :: Nil =>
            cutUntilDelims(d, param, _.is[Token.LeftParen], _.is[Token.RightParen])
          case _ =>
            params.zipWithIndex.find { case (p, _) =>
              p.decltpe.exists(symbolMatcher.matches)
            } match {
              case Some((param, idx)) =>
                if (params.size == idx + 1)
                  cutUntilDelims(d, param, _.is[Token.Comma], _.is[Token.RightParen], keepR = true)
                else {
                  val leftDelim = (t: Token) =>
                    t.is[Token.LeftParen] || t.is[Token.KwImplicit] || t.is[Token.Comma]
                  cutUntilDelims(d, param, leftDelim, _.is[Token.Comma], keepL = true)
                }
              case None => Patch.empty
            }
        }
    }
  }

  private def cutUntilDelims(
      outer: Tree,
      inner: Tree,
      leftDelim: Token => Boolean,
      rightDelim: Token => Boolean,
      keepL: Boolean = false,
      keepR: Boolean = false
  ): Patch = {
    val innerTokens = inner.tokens
    (innerTokens.headOption, innerTokens.lastOption) match {
      case (Some(first), Some(last)) =>
        val outerTokens = outer.tokens
        val maybeDelimL = outerTokens.takeWhile(_ != first).reverseIterator.find(leftDelim)
        val maybeDelimR = outerTokens.takeRightWhile(_ != last).find(rightDelim)
        (maybeDelimL, maybeDelimR) match {
          case (Some(delimL), Some(delimR)) =>
            val toRemove = outerTokens
              .dropWhile(_ != delimL)
              .drop(if (keepL) 1 else 0)
              .dropRightWhile(_ != delimR)
              .dropRight(if (keepR) 1 else 0)
            Patch.removeTokens(toRemove)
          case _ => Patch.empty
        }
      case _ => Patch.empty
    }
  }
}
