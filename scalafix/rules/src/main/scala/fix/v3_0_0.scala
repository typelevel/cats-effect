package fix

import scalafix.v1._

import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val bracketMatcher = SymbolMatcher.normalized("cats/effect/Bracket.")
    val guaranteeMatcher = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")

    doc.tree.collect {
      case t @ q"import cats.effect.Bracket" =>
       Patch.replaceTree(t, "import cats.effect.MonadCancel")

      case t @ q"$x[$f, $e].guarantee($a)($b)" if bracketMatcher.matches(x) =>
        Patch.replaceTree(t, s"MonadCancel[$f, $e].guarantee($a, $b)")

      case t @ q"${guaranteeMatcher(f)}($a)($b)" =>
        Patch.replaceTree(t, s"$f($a, $b)")
    }.asPatch
  }
}
