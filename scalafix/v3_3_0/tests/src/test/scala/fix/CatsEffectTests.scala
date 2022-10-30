package fix

import org.scalatest.FunSuiteLike
import scalafix.testkit.AbstractSemanticRuleSuite

class CatsEffectTests extends AbstractSemanticRuleSuite with FunSuiteLike {
  runAllTests()
}
