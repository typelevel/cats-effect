/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO
import cats.effect.concurrent.{ Deferred, Ref, Semaphore }

object PkgRewrites {
  locally(Deferred)
  locally(cats.effect.concurrent.Deferred)
  def f1(d: cats.effect.concurrent.Deferred[IO, Unit]): Deferred[IO, Unit] = d

  locally(Ref)
  locally(cats.effect.concurrent.Ref)
  def f2(r: cats.effect.concurrent.Ref[IO, Unit]): Ref[IO, Unit] = r

  locally(Semaphore)
  locally(cats.effect.concurrent.Semaphore)
  def f3(s: cats.effect.concurrent.Semaphore[IO]): Semaphore[IO] = s
}
