/*
 * Copyright 2020-2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect

/**
 * [[IOLocal]] provides a handy way of sharing a context within the fiber runtime.
 *
 * [[IO]] stores local states as `scala.collection.immutable.Map[IOLocal[_], Any]` under the
 * hood.
 *
 * That means, two fibers can never access the same [[IOLocal]], they will always be working on
 * their own copies.
 *
 * In some scenarios, [[IOLocal]] can be considered as an alternative to
 * [[https://typelevel.org/cats-mtl/mtl-classes/local.html cats.mtl.Local]]
 *
 * @example
 *   {{{
 *  import cats.Monad
 *  import cats.effect.{IO, IOLocal, Sync, Resource}
 *  import cats.effect.std.{Console, Random}
 *  import cats.syntax.flatMap._
 *  import cats.syntax.functor._
 *
 *  case class TraceId(value: String)
 *
 *  object TraceId {
 *    def gen[F[_]: Sync]: F[TraceId] =
 *     Random.scalaUtilRandom[F].flatMap(_.nextString(8)).map(TraceId(_))
 *  }
 *
 *  trait TraceIdScope[F[_]] {
 *    def get: F[TraceId]
 *    def scope(traceId: TraceId): Resource[F, Unit]
 *  }
 *
 *  object TraceIdScope {
 *    def apply[F[_]](implicit ev: TraceIdScope[F]): TraceIdScope[F] = ev
 *
 *    def fromIOLocal: IO[TraceIdScope[IO]] =
 *      for {
 *        local <- IOLocal(TraceId("global"))
 *      } yield new TraceIdScope[IO] {
 *        def get: IO[TraceId] =
 *          local.get
 *
 *        def scope(traceId: TraceId): Resource[IO, Unit] =
 *          Resource.make(local.getAndSet(traceId))(previous => local.set(previous)).void
 *      }
 *  }
 *
 *  def service[F[_]: Sync: Console: TraceIdScope]: F[String] =
 *    for {
 *      traceId <- TraceId.gen[F]
 *      result <- TraceIdScope[F].scope(traceId).use(_ => callRemote[F])
 *    } yield result
 *
 *  def callRemote[F[_]: Monad: Console: TraceIdScope]: F[String] =
 *    for {
 *      traceId <- TraceIdScope[F].get
 *      _ <- Console[F].println(s"Processing request. TraceId: $${traceId}")
 *    } yield "some response"
 *
 *  TraceIdScope.fromIOLocal.flatMap { implicit traceIdScope: TraceIdScope[IO] =>
 *    service[IO]
 *  }
 *
 *   }}}
 *
 * @tparam A
 *   the type of the local value
 */
sealed trait IOLocal[A] {

  def get: IO[A]

  def set(value: A): IO[Unit]

  def reset: IO[Unit]

  def update(f: A => A): IO[Unit]

  def modify[B](f: A => (A, B)): IO[B]

  def getAndSet(value: A): IO[A]

  def getAndReset: IO[A]

}

object IOLocal {

  /**
   * Creates a new instance of [[IOLocal]] with the given default value.
   *
   * The creation is effectful, because [[IOLocal]] models mutable state, and allocating mutable
   * state is not pure.
   *
   * @param default
   *   the default value
   * @tparam A
   *   the type of the local value
   */
  def apply[A](default: A): IO[IOLocal[A]] =
    IO {
      new IOLocal[A] { self =>
        override def get: IO[A] =
          IO.Local(state => (state, state.get(self).map(_.asInstanceOf[A]).getOrElse(default)))

        override def set(value: A): IO[Unit] =
          IO.Local(state => (state + (self -> value), ()))

        override def reset: IO[Unit] =
          IO.Local(state => (state - self, ()))

        override def update(f: A => A): IO[Unit] =
          get.flatMap(a => set(f(a)))

        override def modify[B](f: A => (A, B)): IO[B] =
          get.flatMap { a =>
            val (a2, b) = f(a)
            set(a2).as(b)
          }

        override def getAndSet(value: A): IO[A] =
          get <* set(value)

        override def getAndReset: IO[A] =
          get <* reset

      }
    }

}
