/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.Async
import cats.effect.ContextShift
import cats.effect.{ContextShift => CtxShift}
import cats.effect.Sync

object ContextShiftRewrites {
  def f1[F[_]](implicit cs: ContextShift[F]): Int = 0

  def f2[F[_]](implicit F: Sync[F], cs: CtxShift[F]): Int = 0

  def f3[F[_]](implicit cs: ContextShift[F], F: Sync[F]): Int = 0

  def f4[F[_]](implicit F1: Sync[F], contextShift: ContextShift[F], F2: Sync[F]): Int = 0

  def f5[F[_]](implicit cs: ContextShift[F], F: Async[F]): F[Unit] = cs.shift
}
