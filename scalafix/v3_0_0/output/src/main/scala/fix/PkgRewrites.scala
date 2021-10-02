package fix

import cats.effect.IO
import cats.{ApplicativeThrow, MonadThrow}
import cats.effect.{Deferred, Ref}
import cats.effect.std.Semaphore

object PkgRewrites {
  locally(Deferred)
  locally(_root_.cats.effect.Deferred)
  def f1(d: _root_.cats.effect.Deferred[IO, Unit]): Deferred[IO, Unit] = d

  locally(Ref)
  locally(_root_.cats.effect.Ref)
  def f2(r: _root_.cats.effect.Ref[IO, Unit]): Ref[IO, Unit] = r

  locally(Semaphore)
  locally(cats.effect.std.Semaphore)
  def f3(s: cats.effect.std.Semaphore[IO]): Semaphore[IO] = s

  def f4[F[_]](F: ApplicativeThrow[F]): F[Unit] = F.unit
  def f5[F[_]](F: MonadThrow[F]): F[Unit] = F.unit
}
