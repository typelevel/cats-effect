/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
import cats.effect.internals.ResourcePlatform
import cats.implicits._

import scala.annotation.tailrec

/**
 * The `Resource` is a data structure that captures the effectful
 * allocation of a resource, along with its finalizer.
 *
 * This can be used to wrap expensive resources. Example:
 *
 * {{{
 *   def open(file: File): Resource[IO, BufferedReader] =
 *     Resource(IO {
 *       val in = new BufferedReader(new FileReader(file))
 *       (in, IO(in.close()))
 *     })
 * }}}
 *
 * Usage is done via [[Resource!.use use]] and note that resource usage nests,
 * because its implementation is specified in terms of [[Bracket]]:
 *
 * {{{
 *   open(file1).use { in1 =>
 *     open(file2).use { in2 =>
 *       readFiles(in1, in2)
 *     }
 *   }
 * }}}
 *
 * `Resource` forms a `MonadError` on the resource type when the
 * effect type has a `cats.MonadError` instance. Nested resources are
 * released in reverse order of acquisition. Outer resources are
 * released even if an inner use or release fails.
 *
 * {{{
 *   def mkResource(s: String) = {
 *     val acquire = IO(println(s"Acquiring $$s")) *> IO.pure(s)
 *     def release(s: String) = IO(println(s"Releasing $$s"))
 *     Resource.make(acquire)(release)
 *   }
 *
 *   val r = for {
 *     outer <- mkResource("outer")
 *     inner <- mkResource("inner")
 *   } yield (outer, inner)
 *
 *   r.use { case (a, b) =>
 *     IO(println(s"Using $$a and $$b"))
 *   }
 * }}}
 *
 * On evaluation the above prints:
 * {{{
 *   Acquiring outer
 *   Acquiring inner
 *   Using outer and inner
 *   Releasing inner
 *   Releasing outer
 * }}}
 *
 * A `Resource` is nothing more than a data structure, an ADT, described by
 * the following node types and that can be interpreted if needed:
 *
 *  - [[cats.effect.Resource.Allocate Allocate]]
 *  - [[cats.effect.Resource.Suspend Suspend]]
 *  - [[cats.effect.Resource.Bind Bind]]
 *
 * Normally users don't need to care about these node types, unless conversions
 * from `Resource` into something else is needed (e.g. conversion from `Resource`
 * into a streaming data type).
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
  def use[B](f: A => F[B])(implicit F: Bracket[F, Throwable]): F[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[F, Any], stack: List[Any => Resource[F, Any]]): F[Any] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(current: Resource[F, Any], stack: List[Any => Resource[F, Any]]): F[Any] = {
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
   * Implementation for the `flatMap` operation, as described via the
   * `cats.Monad` type class.
   */
  def flatMap[B](f: A => Resource[F, B]): Resource[F, B] =
    Bind(this, f)

  /**
    *  Given a mapping function, transforms the resource provided by
    *  this Resource.
    *
    *  This is the standard `Functor.map`.
    */
  def map[B](f: A => B)(implicit F: Applicative[F]): Resource[F, B] =
    flatMap(a => Resource.pure[F, B](f(a)))

  /**
    * Given a natural transformation from `F` to `G`, transforms this
    * Resource from effect `F` to effect `G`.
    */
  def mapK[G[_]](f: F ~> G)(implicit B: Bracket[F, Throwable], D: Defer[G], G: Applicative[G]): Resource[G, A] =
    this match {
      case Allocate(resource) =>
        Allocate(f(resource).map { case (a, r) => (a, r.andThen(u => f(u))) })
      case Bind(source, f0) =>
        Bind(Suspend(D.defer(G.pure(source.mapK(f)))), f0.andThen(_.mapK(f)))
      case Suspend(resource) =>
        Suspend(f(resource).map(_.mapK(f)))
    }

  /**
    * Given a `Resource`, possibly built by composing multiple
    * `Resource`s monadically, returns the acquired resource, as well
    * as an action that runs all the finalizers for releasing it.
    *
    * If the outer `F` fails or is interrupted, `allocated` guarantees
    * that the finalizers will be called. However, if the outer `F`
    * succeeds, it's up to the user to ensure the returned `F[Unit]`
    * is called once `A` needs to be released. If the returned
    * `F[Unit]` is not called, the finalizers will not be run.
    *
    * For this reason, this is an advanced and potentially unsafe api
    * which can cause a resource leak if not used correctly, please
    * prefer [[use]] as the standard way of running a `Resource`
    * program.
    *
    * Use cases include interacting with side-effectful apis that
    * expect separate acquire and release actions (like the `before`
    * and `after` methods of many test frameworks), or complex library
    * code that needs to modify or move the finalizer for an existing
    * resource.
    *
    */
  def allocated(implicit F: Bracket[F, Throwable]): F[(A, F[Unit])] = {

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(
      current: Resource[F, Any],
      stack: List[Any => Resource[F, Any]],
      release: F[Unit]): F[(Any, F[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(
      current: Resource[F, Any],
      stack: List[Any => Resource[F, Any]],
      release: F[Unit]): F[(Any, F[Unit])] =
      current match {
        case Resource.Allocate(resource) =>
          F.bracketCase(resource) {
            case (a, rel) =>
              stack match {
                case Nil => F.pure(a -> F.guarantee(rel(ExitCase.Completed))(release))
                case f0 :: xs => continue(f0(a), xs, F.guarantee(rel(ExitCase.Completed))(release))
              }
          } {
            case (_, ExitCase.Completed) =>
              F.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case Resource.Bind(source, f0) =>
          loop(source, f0.asInstanceOf[Any => Resource[F, Any]] :: stack, release)
        case Resource.Suspend(resource) =>
          resource.flatMap(continue(_, stack, release))
      }

    loop(this.asInstanceOf[Resource[F, Any]], Nil, F.unit).map {
      case (a, release) =>
        (a.asInstanceOf[A], release)
    }
  }

  /**
    * Applies an effectful transformation to the allocated resource. Like a
    * `flatMap` on `F[A]` while maintaining the resource context
    */
  def evalMap[B](f: A => F[B])(implicit F: Applicative[F]): Resource[F, B] =
    this.flatMap(a => Resource.liftF(f(a)))

  /**
    * Applies an effectful transformation to the allocated resource. Like a
    * `flatTap` on `F[A]` while maintaining the resource context
    */
  def evalTap[B](f: A => F[B])(implicit F: Applicative[F]): Resource[F, A] =
    this.evalMap(a => f(a).as(a))
}

object Resource extends ResourceInstances with ResourcePlatform {
  /**
   * Creates a resource from an allocating effect.
   *
   * @see [[make]] for a version that separates the needed resource
   *      with its finalizer tuple in two parameters
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param resource an effect that returns a tuple of a resource and
   *        an effect to release it
   */
  def apply[F[_], A](resource: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, A] =
    Allocate[F, A] {
      resource.map { case (a, release) =>
        (a, (_: ExitCase[Throwable]) => release)
      }
    }

  /**
   * Creates a resource from an allocating effect, with a finalizer
   * that is able to distinguish between [[ExitCase exit cases]].
   *
   * @see [[makeCase]] for a version that separates the needed resource
   *      with its finalizer tuple in two parameters
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param resource an effect that returns a tuple of a resource and
   *        an effectful function to release it
   */
  def applyCase[F[_], A](resource: F[(A, ExitCase[Throwable] => F[Unit])]): Resource[F, A] =
    Allocate(resource)

  /**
   * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
   */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.Suspend(fr)

  /**
   * Creates a resource from an acquiring effect and a release function.
   *
   * This builder mirrors the signature of [[Bracket.bracket]].
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire a function to effectfully acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    apply[F, A](acquire.map(a => a -> release(a)))

  /**
   * Creates a resource from an acquiring effect and a release function that can
   * discriminate between different [[ExitCase exit cases]].
   *
   * This builder mirrors the signature of [[Bracket.bracketCase]].
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire a function to effectfully acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def makeCase[F[_], A](acquire: F[A])(release: (A, ExitCase[Throwable]) => F[Unit])
    (implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, (e: ExitCase[Throwable]) => release(a, e))))

  /**
   * Lifts a pure value into a resource.  The resouce has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate(F.pure((a, (_: ExitCase[Throwable]) => F.unit)))

  /**
   * Lifts an applicative into a resource.  The resource has a no-op release.
   *
   * @param fa the value to lift into a resource
   */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.suspend(fa.map(a => Resource.pure(a)))

  /**
   * Lifts an applicative into a resource as a `FunctionK`.  The resource has a no-op release.
   */
  def liftK[F[_]](implicit F: Applicative[F]): F ~> Resource[F, ?] =
    λ[F ~> Resource[F, ?]](Resource.liftF(_))

  /**
    * Creates a [[Resource]] by wrapping a Java
    * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
    *
    * Example:
    * {{{
    *   import java.io._
    *   import cats.effect._
    *
    *   def reader[F[_]](file: File)(implicit F: Sync[F]): Resource[F, BufferedReader] =
    *     Resource.fromAutoCloseable(F.delay {
    *       new BufferedReader(new FileReader(file))
    *     })
    * }}}
    * @param acquire The effect with the resource to acquire.
    * @param F the effect type in which the resource was acquired and will be released
    * @tparam F the type of the effect
    * @tparam A the type of the autocloseable resource
    * @return a Resource that will automatically close after use
    */
  def fromAutoCloseable[F[_], A <: AutoCloseable](acquire: F[A])(implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.delay(autoCloseable.close()))

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
    resource: F[(A, ExitCase[Throwable] => F[Unit])])
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

  implicit def catsEffectLiftIOForResource[F[_]](implicit F00: LiftIO[F], F10: Applicative[F]): LiftIO[Resource[F, ?]] =
    new ResourceLiftIO[F] {
      def F0 = F00
      def F1 = F10
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

  import Resource.{Allocate, Bind, Suspend}

  protected implicit def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa match {
      case Allocate(fa) =>
        Allocate[F, Either[E, A]](F.attempt(fa).map {
          case Left(error) => (Left(error), (_: ExitCase[Throwable]) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, Any], fs: (Any => Resource[F, A])) =>
        Suspend(F.pure(source).map { source =>
          Bind(attempt(source), (r: Either[E, Any]) => r match {
            case Left(error) => Resource.pure(Left(error))
            case Right(s) => attempt(fs(s))
          })
        })
      case Suspend(resource) =>
        Suspend(resource.attempt.map {
          case Left(error) => Resource.pure(Left(error))
          case Right(fa: Resource[F, A]) => fa.attempt
        })
    }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure(a)
      case Left(e) => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource.applyCase(F.raiseError(e))
}


private[effect] abstract class ResourceMonad[F[_]] extends Monad[Resource[F, ?]] {
  protected implicit def F: Monad[F]

  override def map[A, B](fa: Resource[F, A])(f: A => B): Resource[F, B] =
    fa.map(f)

  def pure[A](a: A): Resource[F, A] =
    Resource.applyCase(F.pure((a, _ => F.unit)))

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

private[effect] abstract class ResourceLiftIO[F[_]] extends LiftIO[Resource[F, ?]] {
  protected implicit def F0: LiftIO[F]
  protected implicit def F1: Applicative[F]

  def liftIO[A](ioa: IO[A]): Resource[F, A] =
    Resource.liftF(F0.liftIO(ioa))
}
