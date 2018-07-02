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
import cats.data.AndThen
import cats.effect.ExitCase.Completed
import cats.implicits._

import scala.annotation.tailrec

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
  import Resource.{Allocate, Bind, Suspend}

  /**
   * Allocates a resource and supplies it to the given function.  The
   * resource is released as soon as the resulting `F[B]` is
   * completed, whether normally or as a raised error.
   *
   * @param f the function to apply to the allocated resource
   * @return the result of applying [F] to
   */
  def use[B, E](f: A => F[B])(implicit F: Bracket[F, E]): F[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[F, Any], stack: List[Any => Resource[F, Any]]): F[Any] =
      loop(current, stack)

    @tailrec
    def loop(current: Resource[F, Any], stack: List[Any => Resource[F, Any]]): F[Any] = {
      current match {
        case Allocate(resource) =>
          F.bracketCase(resource) { case (a, _) =>
            stack match {
              case Nil => f.asInstanceOf[Any => F[Any]](a)
              case f0 :: xs => continue(f0(a), xs)
            }
          } { case ((_, release), ec) =>
            release(ec)
          }
        case Bind(source, f0) =>
          loop(source, f0.asInstanceOf[Any => Resource[F, Any]] :: stack)
        case Suspend(resource) =>
          resource.flatMap(continue(_, stack))
      }
    }
    loop(this.asInstanceOf[Resource[F, Any]], Nil).asInstanceOf[F[B]]
  }

  /**
   * Implementation for the `flatMap` operation, as described via
   * the `cats.Monad` type class.
   */
  def flatMap[B](f: A => Resource[F, B]): Resource[F, B] =
    Bind(this, f)

  /**
   * Implementation for the `attempt` operation, as described via
   * the `cats.MonadError` type class.
   */
  def attempt[E](implicit F: MonadError[F, E]): Resource[F, Either[E, A]] =
    this match {
      case Allocate(fa) =>
        Allocate[F, Either[E, A]](F.attempt(fa).map {
          case Left(error) => (Left(error), (_: ExitCase[_]) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, Any], fs: (Any => Resource[F, A])) =>
        Suspend(F.pure(source).map { source =>
          Bind(source.attempt, (r: Either[E, Any]) => r match {
            case Left(error) => Resource.pure(Left(error))
            case Right(s) => fs(s).attempt
          })
        })
      case Suspend(resource) =>
        Suspend(resource.map(_.attempt))
    }
}

object Resource extends ResourceInstances {
  /**
   * Creates a resource from an allocating effect.
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param allocate an effect that returns a tuple of a resource and
   * an effect to release it
   */
  def apply[F[_], A](allocate: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, A] =
    Allocate[F, A] {
      allocate.map { case (a, release) =>
        (a, (_: ExitCase[_]) => release)
      }
    }

  /**
   * Creates a resource from an acquiring effect and a release function.
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire a function to effectfully acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    Resource[F, A](acquire.map(a => a -> release(a)))

  /**
   * Creates a resource from an acquiring effect and a release function that can
   * discriminate between different [[ExitCase exit cases]].
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire a function to effectfully acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def makeCase[F[_], A](acquire: F[A])(release: (A, ExitCase[_]) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    Allocate[F, A](acquire.map(a => (a, (e: ExitCase[_]) => release(a, e))))

  /**
   * Lifts a pure value into a resource.  The resouce has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate(F.pure((a, (_: ExitCase[_]) => F.unit)))

  /**
   * Lifts an applicative into a resource.  The resource has a no-op release.
   *
   * @param fa the value to lift into a resource
   */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]) =
    make(fa)(_ => F.unit)

  /**
   * Implementation for the `tailRecM` operation, as described via
   * the `cats.Monad` type class.
   */
  def tailRecM[F[_], A, B](a: A)(f: A => Resource[F, Either[A, B]])
    (implicit F: Monad[F]): Resource[F, B] = {

    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r match {
        case Allocate(fea) =>
          Suspend(fea.flatMap {
            case (Left(a), release) =>
              release(Completed).map(_ => tailRecM(a)(f))
            case (Right(b), release) =>
              F.pure(Allocate[F, B](F.pure((b, release))))
          })
        case Suspend(fr) =>
          Suspend(fr.map(continue))
        case Bind(source, fs) =>
          Bind(source, AndThen(fs).andThen(continue))
      }

    continue(f(a))
  }

  /**
   * `Resource` data constructor that wraps an effect allocating a resource,
   * along with its finalizers.
   */
  final case class Allocate[F[_], A](
    resource: F[(A, ExitCase[_] => F[Unit])])
    extends Resource[F, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, A](
    source: Resource[F, S],
    fs: S => Resource[F, A])
    extends Resource[F, A]

  /**
   * `Resource` data constructor that suspends the evaluation of another
   * resource value.
   */
  final case class Suspend[F[_], A](
    resource: F[Resource[F, A]])
    extends Resource[F, A]
}

private[effect] abstract class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectMonadErrorForResource[F[_], E](implicit F0: MonadError[F, E]): MonadError[Resource[F, ?], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A](implicit F0: Monad[F], A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
      def F = F0
    }
}

private[effect] abstract class ResourceInstances0 {
  implicit def catsEffectMonadForResource[F[_]](implicit F0: Monad[F]): Monad[Resource[F, ?]] =
    new ResourceMonad[F] {
      def F = F0
    }

  implicit def catsEffectSemigroupForResource[F[_], A](implicit F0: Monad[F], A0: Semigroup[A]) =
    new ResourceSemigroup[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](implicit F0: Monad[F], K0: SemigroupK[F]) =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
    }
}

private[effect] abstract class ResourceMonadError[F[_], E] extends ResourceMonad[F]
  with MonadError[Resource[F, ?], E] {

  protected implicit def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa.attempt

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure(a)
      case Left(e) => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource(F.raiseError(e))
}


private[effect] abstract class ResourceMonad[F[_]] extends Monad[Resource[F, ?]] {
  protected implicit def F: Monad[F]

  def pure[A](a: A): Resource[F, A] =
    Resource(F.pure(a -> F.unit))

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Resource[F, Either[A, B]]): Resource[F, B] =
    Resource.tailRecM(a)(f)
}

private[effect] abstract class ResourceMonoid[F[_], A] extends ResourceSemigroup[F, A]
  with Monoid[Resource[F, A]] {

  protected implicit def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure(A.empty)
}

private[effect] abstract class ResourceSemigroup[F[_], A] extends Semigroup[Resource[F, A]] {
  protected implicit def F: Monad[F]
  protected implicit def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

private[effect] abstract class ResourceSemigroupK[F[_]] extends SemigroupK[Resource[F, ?]] {
  protected implicit def F: Monad[F]
  protected implicit def K: SemigroupK[F]

  def combineK[A](rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
      xy <- Resource.liftF(K.combineK(x.pure[F], y.pure[F]))
    } yield xy
}
