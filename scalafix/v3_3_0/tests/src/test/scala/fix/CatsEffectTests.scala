package fix

import org.scalatest.funsuite.AnyFunSuiteLike
import scalafix.testkit.AbstractSemanticRuleSuite

class CatsEffectTests extends AbstractSemanticRuleSuite with AnyFunSuiteLike {
  runAllTests()
}
