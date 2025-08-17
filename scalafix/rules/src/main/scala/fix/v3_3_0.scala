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

    doc
      .tree
      .collect {
        // IO.interruptible(false) -> IO.interruptible
        // IO.interruptible(true) -> IO.interruptibleMany
        case t @ q"${IO_M(_)}.interruptible(${Lit.Boolean(many)})" =>
          replaceInterruptible(many, t, s"${IO_S.displayName}")

        // Sync#interruptible(false) -> Sync#interruptible
        // Sync#interruptible(true) -> Sync#interruptibleMany
        case t @ q"${Sync_interruptible_M(Term.Apply.After_4_6_0(interruptible, Term.ArgClause(Lit.Boolean(many) :: _, _)))}" =>
          interruptible.synthetics match {
            case TypeApplyTree(_, TypeRef(_, symbol, _) :: _) :: _ =>
              if (symbol.displayName == "Unit") interruptible match {
                case Term.Select(typeF, _) =>
                  replaceInterruptible(
                    many,
                    t,
                    s"${Sync_S.displayName}[${typeF.symbol.displayName}]"
                  )
                case _ => Patch.empty
              }
              else
                replaceInterruptible(many, t, s"${Sync_S.displayName}[${symbol.displayName}]")
            case _ => Patch.empty
          }
      }
      .asPatch
  }

  def replaceInterruptible(many: Boolean, from: Tree, to: String): Patch =
    if (many)
      Patch.replaceTree(from, s"$to.interruptibleMany")
    else
      Patch.replaceTree(from, s"$to.interruptible")
}
