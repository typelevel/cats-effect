package fix

import scalafix.v1._

import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val guaranteeMatcher = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")

    doc.tree.collect {
      case t @ q"import cats.effect.Bracket" =>
        Patch.replaceTree(t, "import cats.effect.MonadCancel")

      case t @ q"${guaranteeMatcher(f)}($a)($b)" =>
        val f1 = f.toString().replace("Bracket", "MonadCancel")
        Patch.replaceTree(t, s"$f1($a, $b)")
    }.asPatch
  }
}
