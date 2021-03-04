package fix

import cats.effect.IO
import cats.effect.Sync

object SyncRewrites {
  Sync[IO].defer(IO.unit)

  // suspend shouldn't be rewritten at definition site.
  // uncomment when output build is updated with newer CE2 version.
  //
  // def instance[F[_]]: Sync[F] = new Sync[F] {
  //   def pure[A](x: A): F[A] = ???
  //   def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = ???
  //   def raiseError[A](e: Throwable): F[A] = ???
  //   def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
  //     release: (A, cats.effect.ExitCase[Throwable]) => F[Unit]
  //   ): F[B] = ???
  //   def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = ???
  //   def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = ???
  //   def suspend[A](thunk: => F[A]): F[A] = ???
  // }
}
