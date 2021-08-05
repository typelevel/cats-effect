package fix

import scalafix.v1._

class v2_5_3 extends SemanticRule("v2_5_3") {
  override def fix(implicit doc: SemanticDocument): Patch =
    Patch.replaceSymbols(
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/SyncIO.suspend()." -> "defer"
    )
}
