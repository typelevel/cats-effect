/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats._
import cats.implicits._

/**
  * Effectfully allocates and releases a resource.  Forms a
  * `MonadError` on the resource type when the effect type has a
  * [[Bracket]] instance.  Nested resources are released in reverse
  * order of acquisition.  Outer resources are released even if an
  * inner use or release fails.
  *
  * {{{
  * def mkResource(s: String) = {
  *   val acquire = IO(println(s"Acquiring $$s")) *> IO.pure(s)
  *   def release(s: String) = IO(println(s"Releasing $$s"))
  *   Resource.make(acquire)(release)
  * }
  * val r = for {
  *   outer <- mkResource("outer")
  *   inner <- mkResource("inner")
  * } yield (outer, inner)
  * r.use { case (a, b) => IO(println(s"Using $$a and $$b")) }.unsafeRunSync
  * }}}
  *
  * The above prints:
  * {{{
  * Acquiring outer
  * Acquiring inner
  * Using outer and inner
  * Releasing inner
  * Releasing outer
  * }}}
  *
  * @tparam F the effect type in which the resource is allocated and released
  * @tparam A the type of resource
  */
sealed abstract class Resource[F[_], A] {
  /** An effect that returns a tuple of a resource and an effect to
    * release it.
    *
    * Streaming types might implement a bracket operation that keeps
    * the resource open through multiple outputs of a stream.
    */
  def allocate: F[(A, F[Unit])]

  /**
    * Allocates a resource and supplies it to the given function.  The
    * resource is released as soon as the resulting `F[B]` is
    * completed, whether normally or as a raised error.
    *
    * @param f the function to apply to the allocated resource
    * @return the result of applying [F] to
    */
  def use[B, E](f: A => F[B])(implicit F: Bracket[F, E]): F[B] =
    F.bracket(allocate)(a => f(a._1))(_._2)
}

object Resource extends ResourceInstances {
  /** Creates a resource from an allocating effect.
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param allocate an effect that returns a tuple of a resource and
    * an effect to release it
    */
  def apply[F[_], A](allocate: F[(A, F[Unit])]): Resource[F, A] = {
    val a = allocate
    new Resource[F, A] { def allocate = a }
  }

  /** Creates a resource from an acquiring effect and a release function.
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    apply(acquire.map(a => (a -> release(a))))

  /** Lifts a pure value into a resource.  The resouce has a no-op release.
    *
    * @param a the value to lift into a resource
    */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]) =
    Resource(F.pure(a -> F.pure(())))

  /** Lifts an applicative into a resource.  The resource has a no-op release.
    *
    * @param fa the value to lift into a resource
    */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]) =
    make(fa)(_ => F.pure(()))
}

private[effect] abstract class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectBracketForResource[F[_], E](implicit F0: Bracket[F, E]): MonadError[Resource[F, ?], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A, E](implicit F0: Bracket[F, E], A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A, E] {
      def A = A0
      def F = F0
    }
}

private[effect] abstract class ResourceInstances0 {
  implicit def catsEffectSemigroupForResource[F[_], A, E](implicit F0: Bracket[F, E], A0: Semigroup[A]) =
    new ResourceSemigroup[F, A, E] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A, E](implicit F0: Bracket[F, E], K0: SemigroupK[F]) =
    new ResourceSemigroupK[F, E] {
      def F = F0
      def K = K0
    }
}

private[effect] abstract class ResourceMonadError[F[_], E] extends MonadError[Resource[F, ?], E] {
  protected implicit def F: Bracket[F, E]

  def pure[A](a: A): Resource[F, A] =
    Resource(F.pure(a -> F.pure(())))

  def flatMap[A, B](fa: Resource[F,A])(f: A => Resource[F, B]): Resource[F, B] =
    Resource(fa.allocate.flatMap { case (a, disposeA) =>
      f(a).allocate.map { case (b, disposeB) =>
        b -> F.bracket(disposeB)(F.pure)(_ => disposeA)
      }
    })

  def tailRecM[A, B](a: A)(f: A => Resource[F, Either[A, B]]): Resource[F, B] = {
    def step(adis: (A, F[Unit])): F[Either[(A, F[Unit]), (B, F[Unit])]] = {
      val (a, dispose) = adis
      val next: F[(Either[A, B], F[Unit])] = f(a).allocate
      // todo: this might not be stack safe, we might
      // need a tailRecM type thing on bracket.
      def compDisp(d: F[Unit]): F[Unit] =
        F.bracket(d)(F.pure)(_ => dispose)
      next.map {
        case (Left(a), nextDispose) => Left((a, compDisp(nextDispose)))
        case (Right(b), nextDispose) => Right((b, compDisp(nextDispose)))
      }
    }

    Resource(F.tailRecM((a, F.pure(())))(step))
  }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    Resource(fa.allocate.handleErrorWith(e => f(e).allocate))

  def raiseError[A](e: E): cats.effect.Resource[F, A] =
    Resource(F.raiseError(e))
}

private[effect] abstract class ResourceMonoid[F[_], A, E] extends ResourceSemigroup[F, A, E]
    with Monoid[Resource[F, A]] {
  protected implicit def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure(A.empty)
}

private[effect] abstract class ResourceSemigroup[F[_], A, E] extends Semigroup[Resource[F, A]] {
  protected implicit def F: Bracket[F, E]
  protected implicit def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

private[effect] abstract class ResourceSemigroupK[F[_], E] extends SemigroupK[Resource[F, ?]] {
  protected implicit def F: Bracket[F, E]  
  protected implicit def K: SemigroupK[F]

  def combineK[A](rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
      xy <- Resource.liftF(K.combineK(x.pure[F], y.pure[F]))
    } yield xy
}
