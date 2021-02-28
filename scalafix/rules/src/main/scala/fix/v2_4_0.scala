package fix

import scalafix.v1._

class v2_4_0 extends SemanticRule("v2_4_0") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    Patch.replaceSymbols(
      "cats/effect/Resource.liftF()." -> "eval"
    )
  }
}
