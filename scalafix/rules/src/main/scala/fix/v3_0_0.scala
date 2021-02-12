package fix

import scalafix.v1._

import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val Bracket_guarantee_M = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")
    val Bracket_uncancelable_M = SymbolMatcher.exact("cats/effect/Bracket#uncancelable().")

    Patch.replaceSymbols(
      "cats/effect/Async#async()." -> "async_",
      "cats/effect/package.BracketThrow." -> "cats/effect/MonadCancelThrow.",
      "cats/effect/Bracket." -> "cats/effect/MonadCancel.",
      "cats/effect/IO.async()." -> "async_",
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/Resource.liftF()." -> "eval",
      "cats/effect/concurrent/Deferred." -> "cats/effect/Deferred.",
      "cats/effect/concurrent/Ref." -> "cats/effect/Ref.",
      "cats/effect/concurrent/Semaphore." -> "cats/effect/std/Semaphore."
    ) +
      doc.tree.collect {
        // Bracket#guarantee(a)(b) -> MonadCancel#guarantee(a, b)
        case t @ q"${Bracket_guarantee_M(_)}($a)($_)" =>
          a.tokens.lastOption.fold(Patch.empty) { tok =>
            val parens = t.tokens.dropWhile(_ != tok).drop(1).take(2)
            Patch.addRight(tok, ", ") + Patch.removeTokens(parens)
          }

        // Bracket#uncancelable(a) -> MonadCancel#uncancelable(_ => a)
        case q"${Bracket_uncancelable_M(_)}($a)" =>
          Patch.addLeft(a, "_ => ")
      }.asPatch
  }
}
