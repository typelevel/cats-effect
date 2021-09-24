package fix

import scalafix.v1._

import scala.meta.Token._
import scala.meta._

class v3_3_0 extends SemanticRule("v3_3_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val IO_M = SymbolMatcher.exact("cats/effect/IO.")
    val Sync_interruptible_M = SymbolMatcher.exact("cats/effect/kernel/Sync#interruptible().")

    val IO_S = Symbol("cats/effect/IO.")
    val Sync_S = Symbol("cats/effect/Sync#")

    doc.tree.collect {
      case t @ q"${IO_M(_)}.interruptible(true)" =>
        Patch.replaceTree(t, s"${IO_S.displayName}.interruptibleMany")

      case t @ q"${IO_M(_)}.interruptible(false)" =>
        Patch.replaceTree(t, s"${IO_S.displayName}.interruptible")

      case t @ Sync_interruptible_M(Term.Apply(interruptible, Lit.Boolean(many) :: _)) =>
        interruptible.synthetics match {
          case TypeApplyTree(_, TypeRef(_, symbol, _) :: _) :: _ =>
            if (many)
              Patch.replaceTree(
                t,
                s"${Sync_S.displayName}[${symbol.displayName}].interruptibleMany"
              )
            else
              Patch.replaceTree(
                t,
                s"${Sync_S.displayName}[${symbol.displayName}].interruptible"
              )
          case _ => Patch.empty
        }
    }.asPatch
  }
}
