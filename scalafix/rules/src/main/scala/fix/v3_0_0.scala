package fix

import scalafix.v1._

import scala.meta.Token._
import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val Blocker_M = SymbolMatcher.normalized("cats/effect/Blocker.")
    val Blocker_delay_M = SymbolMatcher.exact("cats/effect/Blocker#delay().")
    val Bracket_guarantee_M = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")
    val Bracket_uncancelable_M = SymbolMatcher.exact("cats/effect/Bracket#uncancelable().")
    val Concurrent_M = SymbolMatcher.normalized("cats/effect/Concurrent.")
    val ContextShift_M = SymbolMatcher.normalized("cats/effect/ContextShift.")
    val ContextShift_shift_M = SymbolMatcher.exact("cats/effect/ContextShift#shift().")
    val IO_M = SymbolMatcher.normalized("cats/effect/IO.")
    val Parallel_M = SymbolMatcher.normalized("cats/Parallel.")

    val Resource_S = Symbol("cats/effect/Resource#")
    val Spawn_S = Symbol("cats/effect/Spawn#")
    val Sync_S = Symbol("cats/effect/Sync#")

    Patch.replaceSymbols(
      "cats/effect/package.ApplicativeThrow." -> "cats/ApplicativeThrow.",
      "cats/effect/package.MonadThrow." -> "cats/MonadThrow.",
      "cats/effect/package.BracketThrow." -> "cats/effect/MonadCancelThrow.",
      "cats/effect/Bracket." -> "cats/effect/MonadCancel.",
      "cats/effect/IO.async()." -> "async_",
      "cats/effect/Async#async()." -> "async_",
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/Sync#suspend()." -> "defer",
      "cats/effect/ResourceLike#parZip()." -> "both",
      "cats/effect/Resource.liftF()." -> "eval",
      "cats/effect/Timer." -> "cats/effect/Temporal.",
      "cats/effect/concurrent/Deferred." -> "cats/effect/Deferred.",
      "cats/effect/concurrent/Ref." -> "cats/effect/Ref.",
      "cats/effect/concurrent/Semaphore." -> "cats/effect/std/Semaphore."
    ) +
      doc
        .tree
        .collect {
          // Bracket#guarantee(a)(b) -> MonadCancel#guarantee(a, b)
          case t @ q"${Bracket_guarantee_M(_)}($a)($b)" =>
            fuseParameterLists(t, a, b)

          // Bracket#uncancelable(a) -> MonadCancel#uncancelable(_ => a)
          case q"${Bracket_uncancelable_M(_)}($a)" =>
            Patch.addLeft(a, "_ => ")

          // Blocker[F] -> Resource.unit[F]
          case t @ Term.ApplyType.After_4_6_0(Blocker_M(_), Type.ArgClause(List(typeF))) =>
            Patch.addGlobalImport(Resource_S) +
              Patch.replaceTree(t, s"${Resource_S.displayName}.unit[$typeF]")

          // Blocker#delay[F, A] -> Sync[F].blocking
          case t @ Term.ApplyType.After_4_6_0(Blocker_delay_M(_), Type.ArgClause(List(typeF, _))) =>
            Patch.addGlobalImport(Sync_S) +
              Patch.replaceTree(t, s"${Sync_S.displayName}[$typeF].blocking")

          // Blocker#delay -> Sync[F].blocking
          case t @ Term.Select(blocker, Blocker_delay_M(_)) =>
            t.synthetics match {
              case TypeApplyTree(_, UniversalType(_, TypeRef(_, symbol, _)) :: _) :: _ =>
                Patch.addGlobalImport(Sync_S) +
                  Patch.replaceTree(t, s"${Sync_S.displayName}[${symbol.displayName}].blocking")
              case _ => Patch.empty
            }

          // ContextShift#shift -> Spawn[F].cede
          case t @ Term.Select(cs, ContextShift_shift_M(_)) =>
            cs.symbol.info.map(_.signature) match {
              case Some(ValueSignature(TypeRef(_, _, TypeRef(_, symbol, _) :: _))) =>
                Patch.addGlobalImport(Spawn_S) +
                  Patch.replaceTree(t, s"${Spawn_S.displayName}[${symbol.displayName}].cede")
              case _ => Patch.empty
            }

          case t @ ImporteeNameOrRename(Blocker_M(_)) =>
            Patch.removeImportee(t)

          case t @ ImporteeNameOrRename(ContextShift_M(_)) =>
            Patch.removeImportee(t)

          case d: Defn.Def =>
            List(
              removeParam(d, _.decltpe.exists(Blocker_M.matches)),
              removeParam(d, _.decltpe.exists(ContextShift_M.matches)),
              // implicit Concurrent[IO] ->
              removeParam(
                d,
                p =>
                  p.mods.nonEmpty && p.decltpe.exists {
                    case Type.Apply.After_4_6_0(Concurrent_M(_), Type.ArgClause(List(IO_M(_)))) => true
                    case _ => false
                  }
              ),
              // implicit Parallel[F] -> if implicit Concurrent[F] + import cats.effect.implicits._
              removeParam(
                d,
                ps =>
                  ps.exists(p => isImplicit(p) && p.decltpe.exists(Concurrent_M.matches)) &&
                    ps.exists(p => isImplicit(p) && p.decltpe.exists(Parallel_M.matches)),
                p => isImplicit(p) && p.decltpe.exists(Parallel_M.matches)
              ).map(_ + Patch.addGlobalImport(wildcardImport(q"cats.effect.implicits")))
            ).flatten.asPatch
        }
        .asPatch
  }

  private object ImporteeNameOrRename {
    def unapply(importee: Importee): Option[Name] =
      importee match {
        case Importee.Name(x) => Some(x)
        case Importee.Rename(x, _) => Some(x)
        case _ => None
      }
  }

  private def isImplicit(param: Term.Param): Boolean =
    param.mods.exists(_.is[Mod.Implicit])

  private def wildcardImport(ref: Term.Ref): Importer =
    Importer(ref, List(Importee.Wildcard()))

  // tree @ f(param1)(param2) -> f(param1, param2)
  private def fuseParameterLists(tree: Tree, param1: Tree, param2: Tree): Patch =
    (param1.tokens.lastOption, param2.tokens.headOption) match {
      case (Some(lastA), Some(firstB)) =>
        val between =
          tree.tokens.dropWhile(_ != lastA).drop(1).dropRightWhile(_ != firstB).dropRight(1)
        val maybeParen1 = between.find(_.is[RightParen])
        val maybeParen2 = between.reverseIterator.find(_.is[LeftParen])
        (maybeParen1, maybeParen2) match {
          case (Some(p1), Some(p2)) =>
            val toAdd = if (lastA.end == p1.start && p1.end == p2.start) ", " else ","
            Patch.replaceToken(p1, toAdd) + Patch.removeToken(p2)
          case _ => Patch.empty
        }
      case _ => Patch.empty
    }

  // f(p1, p2, p3) -> f(p1, p3) if paramMatcher(p2)
  private def removeParam(d: Defn.Def, paramMatcher: Term.Param => Boolean)(
      implicit doc: SemanticDocument): Option[Patch] =
    removeParam(d, _.exists(paramMatcher), paramMatcher)

  private def removeParam(
      d: Defn.Def,
      paramsMatcher: List[Term.Param] => Boolean,
      paramMatcher: Term.Param => Boolean
  )(implicit doc: SemanticDocument): Option[Patch] = {
    d.paramClauses.map(_.values).find(paramsMatcher).flatMap {
      // There is only one parameter, so we're removing the complete parameter list.
      case param :: Nil =>
        cutUntilDelims(d, param, _.is[LeftParen], _.is[RightParen])
      case params =>
        params.zipWithIndex.find { case (p, _) => paramMatcher(p) } flatMap {
          case (p, idx) =>
            // Remove the first parameter.
            if (idx == 0) {
              if (isImplicit(p))
                cutUntilDelims(d, p, _.is[KwImplicit], _.is[Comma], keepL = true)
              else
                cutUntilDelims(d, p, _.is[LeftParen], _.is[Ident], keepL = true, keepR = true)
            }
            // Remove the last parameter.
            else if (params.size == idx + 1)
              cutUntilDelims(d, p, _.is[Comma], _.is[RightParen], keepR = true)
            // Remove inside the parameter list.
            else
              cutUntilDelims(d, p, _.is[Comma], _.is[Comma], keepL = true)
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
  ): Option[Patch] = {
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
            Some(Patch.removeTokens(toRemove))
          case _ => None
        }
      case _ => None
    }
  }
}
