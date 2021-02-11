package fix

import scalafix.v1._

import scala.meta._
import scala.meta.transversers.SimpleTraverser

class v3_0_0 extends SemanticRule("v3_0_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val bracketMatcher = SymbolMatcher.normalized("cats/effect/Bracket.")
    val guaranteeMatcher = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")
    val uncancelableMatcher = SymbolMatcher.exact("cats/effect/Bracket#uncancelable().")

    Patch.replaceSymbols("cats/effect/IO.suspend()." -> "defer") +
      collect(doc.tree) {
        case bracketMatcher(t @ Name(_)) =>
          Patch.replaceTree(t, toMonadCancel(t)) -> List.empty

        case t @ q"${guaranteeMatcher(f)}($a)($b)" =>
          Patch.replaceTree(t, s"${toMonadCancel(f)}($a, $b)") -> List(a, b)

        case t @ q"${uncancelableMatcher(f)}($a)" =>
          Patch.replaceTree(t, s"${toMonadCancel(f)}(_ => $a)") -> List(a)
      }.asPatch
  }

  private def toMonadCancel(tree: Tree): String =
    tree.toString().replace("Bracket", "MonadCancel")

  // Allows the caller to control the traversal in case of a match.
  // The traversal continues with the given list of trees on a match
  // instead of all children of the current tree.
  //
  // The main purpose of this is to prevent the clash of 2 or more
  // patches that target overlapping trees.
  //
  // Inspired by https://gitter.im/scalacenter/scalafix?at=5fe0b699ce40bd3cdbf3ca8a
  def collect[T](tree: Tree)(fn: PartialFunction[Tree, (T, List[Tree])]): List[T] = {
    val liftedFn = fn.lift
    val buf = scala.collection.mutable.ListBuffer[T]()
    object traverser extends SimpleTraverser {
      override def apply(tree: Tree): Unit = {
        liftedFn(tree) match {
          case Some((t, children)) =>
            buf += t
            children.foreach(apply)
          case None =>
            super.apply(tree)
        }
      }
    }
    traverser(tree)
    buf.toList
  }
}
