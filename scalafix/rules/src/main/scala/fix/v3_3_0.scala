package fix

import scalafix.v1._

import scala.meta.Token._
import scala.meta._

class v3_3_0 extends SemanticRule("v3_3_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val IO_M = SymbolMatcher.exact("cats/effect/IO.")

    val IO_S = Symbol("cats/effect/IO#")

    doc.tree.collect {
      case t @ q"${IO_M(_)}.interruptible(true)" =>
        Patch.replaceTree(t, s"${IO_S.displayName}.interruptibleMany")

      case t @ q"${IO_M(_)}.interruptible(false)" =>
        Patch.replaceTree(t, s"${IO_S.displayName}.interruptible")
    }.asPatch
  }
}
