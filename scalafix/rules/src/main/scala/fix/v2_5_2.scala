package fix

import scalafix.v1._

class v2_5_2 extends SemanticRule("v2_5_2") {
  override def fix(implicit doc: SemanticDocument): Patch =
    Patch.replaceSymbols(
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/SyncIO.suspend()." -> "defer"
    )
}
