/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.ContextShift
import cats.effect.{ ContextShift => CtxShift }
import cats.effect.Sync

object ContextShiftRewrites {
  def f1[F[_]](implicit cs: ContextShift[F]): Int = 0

  // TODO
  //def f2[F[_]](implicit cs: ContextShift[F], F: Sync[F]): Int = 0
}
