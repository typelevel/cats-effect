/*
 * Copyright 2020 Typelevel
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

package cats.effect.kernel

import cats._
import cats.data.AndThen
import cats.syntax.all._
import cats.effect.kernel.implicits._

import scala.annotation.tailrec
import Resource.ExitCase

/**
 * The `Resource` is a data structure that captures the effectful
 * allocation of a resource, along with its finalizer.
 *
 * This can be used to wrap expensive resources. Example:
 *
 * {{{
 *   def open(file: File): Resource[IO, Throwable, BufferedReader] =
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
 *
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
sealed abstract class Resource[+F[_], E, +A] {
  private[effect] type F0[x] <: F[x]

  import Resource.{Allocate, Bind, Suspend}

  private[effect] def fold[G[x] >: F[x], B](
      onOutput: A => G[B],
      onRelease: G[Unit] => G[Unit]
  )(implicit G: Resource.Bracket[G, E]): G[B] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[A]
    final case class Frame[AA, BB](head: AA => Resource[G, E, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](current: Resource[G, E, C], stack: Stack[C]): G[B] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](current: Resource[G, E, C], stack: Stack[C]): G[B] =
      current.invariant match {
        case Allocate(resource) =>
          G.bracketCase(resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput(a)
                case Frame(head, tail) => continue(head(a), tail)
              }
          } {
            case ((_, release), ec) =>
              onRelease(release(ec))
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack))
        case Suspend(resource) =>
          G.flatMap(resource)(continue(_, stack))
      }
    loop(this, Nil)
  }

  /**
   * Allocates a resource and supplies it to the given function.
   * The resource is released as soon as the resulting `F[B]` is
   * completed, whether normally or as a raised error.
   *
   * @param f the function to apply to the allocated resource
   * @return the result of applying [F] to
   */
  def use[G[x] >: F[x], B](f: A => G[B])(implicit G: Resource.Bracket[G, E]): G[B] =
    fold[G, B](f, identity)

  /**
   * Allocates a resource with a non-terminating use action.
   * Useful to run programs that are expressed entirely in `Resource`.
   *
   * The finalisers run when the resulting program fails or gets interrupted.
   */
  def useForever[G[x] >: F[x]](implicit G: GenSpawn[G, E]): G[Nothing] =
    use[G, Nothing](_ => G.never)

  /**
   * Allocates two resources concurrently, and combines their results in a tuple.
   *
   * The finalizers for the two resources are also run concurrently with each other,
   * but within _each_ of the two resources, nested finalizers are run in the usual
   * reverse order of acquisition.
   *
   * Note that `Resource` also comes with a `cats.Parallel` instance
   * that offers more convenient access to the same functionality as
   * `parZip`, for example via `parMapN`:
   *
   * {{{
   *   def mkResource(name: String) = {
   *     val acquire =
   *       IO(scala.util.Random.nextInt(1000).millis).flatMap(IO.sleep) *>
   *       IO(println(s"Acquiring $$name")).as(name)
   *
   *     val release = IO(println(s"Releasing $$name"))
   *     Resource.make(acquire)(release)
   *   }
   *
   *  val r = (mkResource("one"), mkResource("two"))
   *             .parMapN((s1, s2) => s"I have \$s1 and \$s2")
   *             .use(msg => IO(println(msg)))
   * }}}
   */
  def parZip[G[x] >: F[x]: GenConcurrent[*[_], E], B](
      that: Resource[G, E, B]
  ): Resource[G, E, (A, B)] = {
    type Update = (G[Unit] => G[Unit]) => G[Unit]

    def allocate[C](r: Resource[G, E, C], storeFinalizer: Update): G[C] =
      r.fold[G, C](
        _.pure[G],
        release => storeFinalizer(Resource.Bracket[G, E].guarantee(_)(release))
      )

    val bothFinalizers = Ref.of(().pure[G] -> ().pure[G])

    Resource.make(bothFinalizers)(_.get.flatMap(_.parTupled).void).evalMap { store =>
      val leftStore: Update = f => store.update(_.leftMap(f))
      val rightStore: Update =
        f =>
          store.update(t => (t._1, f(t._2))) // _.map(f) doesn't work on 0.25.0 for some reason

      (allocate(this, leftStore), allocate(that, rightStore)).parTupled
    }
  }

  /**
   * Implementation for the `flatMap` operation, as described via the
   * `cats.Monad` type class.
   */
  def flatMap[G[x] >: F[x], B](f: A => Resource[G, E, B]): Resource[G, E, B] =
    Bind(this, f)

  /**
   *  Given a mapping function, transforms the resource provided by
   *  this Resource.
   *
   *  This is the standard `Functor.map`.
   */
  def map[G[x] >: F[x], B](f: A => B)(implicit F: Applicative[G]): Resource[G, E, B] =
    flatMap(a => Resource.pure(f(a)))

  /**
   * Given a natural transformation from `F` to `G`, transforms this
   * Resource from effect `F` to effect `G`.
   */
  def mapK[G[x] >: F[x], H[_]](
      f: G ~> H
  )(implicit D: Defer[H], G: Applicative[H]): Resource[H, E, A] =
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
   */
  def allocated[G[x] >: F[x], B >: A](implicit G: Resource.Bracket[G, E]): G[(B, G[Unit])] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[B]
    final case class Frame[AA, BB](head: AA => Resource[G, E, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](
        current: Resource[G, E, C],
        stack: Stack[C],
        release: G[Unit]): G[(B, G[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](
        current: Resource[G, E, C],
        stack: Stack[C],
        release: G[Unit]): G[(B, G[Unit])] =
      current.invariant match {
        case Allocate(resource) =>
          G.bracketCase(resource) {
            case (a, rel) =>
              stack match {
                case Nil =>
                  G.pure((a: B) -> G.guarantee(rel(ExitCase.Succeeded))(release))
                case Frame(head, tail) =>
                  continue(head(a), tail, G.guarantee(rel(ExitCase.Succeeded))(release))
              }
          } {
            case (_, ExitCase.Succeeded) =>
              G.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack), release)
        case Suspend(resource) =>
          G.flatMap(resource)(continue(_, stack, release))
      }

    loop(this, Nil, G.unit)
  }

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatMap` on `F[A]` while maintaining the resource context
   */
  def evalMap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G]): Resource[G, E, B] =
    this.flatMap(a => Resource.liftF(f(a)))

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatTap` on `F[A]` while maintaining the resource context
   */
  def evalTap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G]): Resource[G, E, A] =
    this.evalMap(a => f(a).as(a))

  /**
   * Converts this to an `InvariantResource` to facilitate pattern matches
   * that Scala 2 cannot otherwise handle correctly.
   */
  private[effect] def invariant: Resource.InvariantResource[F0, E, A]
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
  def apply[F[_], E, A](resource: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, E, A] =
    Allocate[F, E, A] {
      resource.map {
        case (a, release) =>
          (a, (_: ExitCase[E]) => release)
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
  def applyCase[F[_], E, A](resource: F[(A, ExitCase[E] => F[Unit])]): Resource[F, E, A] =
    Allocate(resource)

  /**
   * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
   */
  def suspend[F[_], E, A](fr: F[Resource[F, E, A]]): Resource[F, E, A] =
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
  def make[F[_], E, A](acquire: F[A])(release: A => F[Unit])(
      implicit F: Functor[F]): Resource[F, E, A] =
    apply(acquire.map(a => a -> release(a)))

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
  def makeCase[F[_], E, A](
      acquire: F[A]
  )(release: (A, ExitCase[E]) => F[Unit])(implicit F: Functor[F]): Resource[F, E, A] =
    applyCase(acquire.map(a => (a, e => release(a, e))))

  /**
   * Lifts a pure value into a resource. The resource has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], E, A](a: A)(implicit F: Applicative[F]): Resource[F, E, A] =
    Allocate((a, (_: ExitCase[E]) => F.unit).pure[F])

  /**
   * Lifts an applicative into a resource. The resource has a no-op release.
   * Preserves interruptibility of `fa`.
   *
   * @param fa the value to lift into a resource
   */
  def liftF[F[_], E, A](fa: F[A])(implicit F: Applicative[F]): Resource[F, E, A] =
    Resource.suspend(fa.map(a => Resource.pure[F, E, A](a)))

  /**
   * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
   */
  def liftK[F[_], E](implicit F: Applicative[F]): F ~> Resource[F, E, *] =
    new (F ~> Resource[F, E, *]) {
      def apply[A](fa: F[A]): Resource[F, E, A] = Resource.liftF(fa)
    }

  /**
   * Like `Resource`, but invariant in `F`. Facilitates pattern matches that Scala 2 cannot
   * otherwise handle correctly.
   */
  private[effect] sealed trait InvariantResource[F[_], E, +A] extends Resource[F, E, A] {
    private[effect] type F0[x] = F[x]

    def invariant: InvariantResource[F0, E, A] = this
  }

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
   *
   * In most real world cases, implementors of AutoCloseable are
   * blocking as well, so the close action runs in the blocking
   * context.
   *
   * Example:
   * {{{
   *   import cats.effect._
   *   import scala.io.Source
   *
   *   def reader[F[_]](data: String)(implicit F: Sync[F]): GenResource[F, E, Source] =
   *     Resource.fromAutoCloseable(F.blocking {
   *       Source.fromString(data)
   *     })
   * }}}
   * @param acquire The effect with the resource to acquire.
   * @param F the effect type in which the resource was acquired and will be released
   * @tparam F the type of the effect
   * @tparam A the type of the autocloseable resource
   * @return a Resource that will automatically close after use
   */
  def fromAutoCloseable[F[_], A <: AutoCloseable](acquire: F[A])(
      implicit F: Sync[F]): Resource[F, Throwable, A] =
    Resource.make(acquire)(autoCloseable => F.blocking(autoCloseable.close()))

  /**
   * `Resource` data constructor that wraps an effect allocating a resource,
   * along with its finalizers.
   */
  final case class Allocate[F[_], E, A](resource: F[(A, ExitCase[E] => F[Unit])])
      extends InvariantResource[F, E, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, E, +A](source: Resource[F, E, S], fs: S => Resource[F, E, A])
      extends InvariantResource[F, E, A]

  /**
   * `Resource` data constructor that suspends the evaluation of another
   * resource value.
   */
  final case class Suspend[F[_], E, A](resource: F[Resource[F, E, A]])
      extends InvariantResource[F, E, A]

  /**
   * Type for signaling the exit condition of an effectful
   * computation, that may either succeed, fail with an error or
   * get canceled.
   *
   * The types of exit signals are:
   *
   *  - [[ExitCase$.Succeeded Succeeded]]: for successful completion
   *  - [[ExitCase$.Error Error]]: for termination in failure
   *  - [[ExitCase$.Canceled Canceled]]: for abortion
   */
  sealed trait ExitCase[+E] extends Product with Serializable
  object ExitCase {

    /**
     * An [[ExitCase]] that signals successful completion.
     *
     * Note that "successful" is from the type of view of the
     * `MonadError` type that's implementing [[Bracket]].
     * When combining such a type with `EitherT` or `OptionT` for
     * example, this exit condition might not signal a successful
     * outcome for the user, but it does for the purposes of the
     * `bracket` operation. <-- TODO still true?
     */
    case object Succeeded extends ExitCase[Nothing]

    /**
     * An [[ExitCase]] signaling completion in failure.
     */
    final case class Errored[E](e: E) extends ExitCase[E]

    /**
     * An [[ExitCase]] signaling that the action was aborted.
     *
     * As an example this can happen when we have a cancelable data type,
     * like [[IO]] and the task yielded by `bracket` gets canceled
     * when it's at its `use` phase.
     *
     * Thus [[Bracket]] allows you to observe interruption conditions
     * and act on them.
     */
    case object Canceled extends ExitCase[Nothing]
  }

  @annotation.implicitNotFound(
    "Cannot find an instance for Resource.Bracket. This normally means you need to add implicit evidence of MonadCancel[${F}, Throwable]")
  trait Bracket[F[_], E] extends MonadError[F, E] {
    def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
        release: (A, ExitCase[E]) => F[Unit]): F[B]

    def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
      bracketCase(acquire)(use)((a, _) => release(a))

    def guarantee[A](fa: F[A])(finalizer: F[Unit]): F[A] =
      bracket(unit)(_ => fa)(_ => finalizer)

    def guaranteeCase[A](fa: F[A])(finalizer: ExitCase[E] => F[Unit]): F[A] =
      bracketCase(unit)(_ => fa)((_, e) => finalizer(e))
  }

  trait Bracket0 {
    implicit def catsEffectResourceBracketForSync[F[_]](
        implicit F0: Sync[F]): Bracket[F, Throwable] =
      new SyncBracket[F] {
        implicit protected def F: Sync[F] = F0
      }

    trait SyncBracket[F[_]] extends Bracket[F, Throwable] {
      implicit protected def F: Sync[F]

      def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
          release: (A, ExitCase[Throwable]) => F[Unit]): F[B] =
        flatMap(acquire) { a =>
          val handled = onError(use(a)) {
            case e => void(attempt(release(a, ExitCase.Errored(e))))
          }
          flatMap(handled)(b => as(attempt(release(a, ExitCase.Succeeded)), b))
        }

      def pure[A](x: A): F[A] = F.pure(x)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }
  }

  object Bracket extends Bracket0 {
    def apply[F[_], E](implicit F: Bracket[F, E]): F.type = F

    implicit def bracketMonadCancel[F[_], E](
        implicit F0: MonadCancel[F, E]
    ): Bracket[F, E] =
      new MonadCancelBracket[F, E] {
        implicit protected def F: MonadCancel[F, E] = F0
      }

    trait MonadCancelBracket[F[_], E] extends Bracket[F, E] {
      implicit protected def F: MonadCancel[F, E]

      def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
          release: (A, ExitCase[E]) => F[Unit]): F[B] =
        F.uncancelable { poll =>
          flatMap(acquire) { a =>
            val finalized = F.onCancel(poll(use(a)), release(a, ExitCase.Canceled))
            val handled = onError(finalized) {
              case e => void(attempt(release(a, ExitCase.Errored(e))))
            }
            flatMap(handled)(b => as(attempt(release(a, ExitCase.Succeeded)), b))
          }
        }
      def pure[A](x: A): F[A] = F.pure(x)
      def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: E): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }
  }

  /**
   * Newtype encoding for a `Resource` datatype that has a `cats.Applicative`
   * capable of doing parallel processing in `ap` and `map2`, needed
   * for implementing `cats.Parallel`.
   *
   * Helpers are provided for converting back and forth in `Par.apply`
   * for wrapping any `IO` value and `Par.unwrap` for unwrapping.
   *
   * The encoding is based on the "newtypes" project by
   * Alexander Konovalov, chosen because it's devoid of boxing issues and
   * a good choice until opaque types will land in Scala.
   * [[https://github.com/alexknvl/newtypes alexknvl/newtypes]].
   */
  type Par[+F[_], +E, +A] = Par.Type[F, E, A]

  object Par {
    type Base
    trait Tag extends Any
    type Type[+F[_], +E, +A] <: Base with Tag

    def apply[F[_], E, A](fa: Resource[F, E, A]): Type[F, E, A] =
      fa.asInstanceOf[Type[F, E, A]]

    def unwrap[F[_], E, A](fa: Type[F, E, A]): Resource[F, E, A] =
      fa.asInstanceOf[Resource[F, E, A]]
  }
}

abstract private[effect] class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectMonadErrorForResource[F[_], E](
      implicit F0: MonadError[F, E]): MonadError[Resource[F, E, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], E, A](
      implicit F0: Monad[F],
      A0: Monoid[A]): Monoid[Resource[F, E, A]] =
    new ResourceMonoid[F, E, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectCommutativeApplicativeForResourcePar[F[_], E](
      implicit F: GenConcurrent[F, E]
  ): CommutativeApplicative[Resource.Par[F, E, *]] =
    new ResourceParCommutativeApplicative[F, E] {
      def F0 = F
    }

  implicit def catsEffectParallelForResource[F0[_], E](implicit F: GenConcurrent[F0, E])
      : Parallel.Aux[Resource[F0, E, *], Resource.Par[F0, E, *]] =
    new ResourceParallel[F0, E] {
      def F0 = catsEffectCommutativeApplicativeForResourcePar
      def F1 = catsEffectMonadForResource
    }
}

abstract private[effect] class ResourceInstances0 {
  implicit def catsEffectMonadForResource[F[_], E](
      implicit F0: Monad[F]): Monad[Resource[F, E, *]] =
    new ResourceMonad[F, E] {
      def F = F0
    }

  implicit def catsEffectSemigroupForResource[F[_], E, A](
      implicit F0: Monad[F],
      A0: Semigroup[A]): ResourceSemigroup[F, E, A] =
    new ResourceSemigroup[F, E, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], E, A](
      implicit F0: Resource.Bracket[F, E],
      K0: SemigroupK[F],
      G0: Ref.Make[F]): ResourceSemigroupK[F, E] =
    new ResourceSemigroupK[F, E] {
      def F = F0
      def K = K0
      def G = G0
    }
}

abstract private[effect] class ResourceMonadError[F[_], E]
    extends ResourceMonad[F, E]
    with MonadError[Resource[F, E, *], E] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, E, A]): Resource[F, E, Either[E, A]] =
    fa match {
      case Allocate(fa) =>
        Allocate[F, E, Either[E, A]](F.attempt(fa).map {
          case Left(error) => (Left(error), (_: ExitCase[E]) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, E, s], fs) =>
        Suspend(F.pure(source).map[Resource[F, E, Either[E, A]]] { source =>
          Bind(
            attempt(source),
            (r: Either[E, s]) =>
              r match {
                case Left(error) => Resource.pure[F, E, Either[E, A]](Left(error))
                case Right(s) => attempt(fs(s))
              })
        })
      case Suspend(resource) =>
        Suspend(F.attempt(resource) map {
          case Left(error) => Resource.pure[F, E, Either[E, A]](Left(error))
          case Right(fa: Resource[F, E, A]) => attempt(fa)
        })
    }

  def handleErrorWith[A](fa: Resource[F, E, A])(f: E => Resource[F, E, A]): Resource[F, E, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure(a)
      case Left(e) => f(e)
    }

  def raiseError[A](e: E): Resource[F, E, A] =
    Resource.applyCase[F, E, A](F.raiseError(e))
}

abstract private[effect] class ResourceMonad[F[_], E] extends Monad[Resource[F, E, *]] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: Monad[F]

  override def map[A, B](fa: Resource[F, E, A])(f: A => B): Resource[F, E, B] =
    fa.map(f)

  def pure[A](a: A): Resource[F, E, A] =
    Resource.applyCase[F, E, A](F.pure((a, _ => F.unit)))

  def flatMap[A, B](fa: Resource[F, E, A])(f: A => Resource[F, E, B]): Resource[F, E, B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Resource[F, E, Either[A, B]]): Resource[F, E, B] = {
    def continue(r: Resource[F, E, Either[A, B]]): Resource[F, E, B] =
      r.invariant match {
        case Allocate(resource) =>
          Suspend(F.flatMap(resource) {
            case (eab, release) =>
              (eab: Either[A, B]) match {
                case Left(a) =>
                  F.map(release(ExitCase.Succeeded))(_ => tailRecM(a)(f))
                case Right(b) =>
                  F.pure[Resource[F, E, B]](Allocate[F, E, B](F.pure((b, release))))
              }
          })
        case Suspend(resource) =>
          Suspend(F.map(resource)(continue))
        case b: Bind[r.F0, s, E, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }
}

abstract private[effect] class ResourceMonoid[F[_], E, A]
    extends ResourceSemigroup[F, E, A]
    with Monoid[Resource[F, E, A]] {
  implicit protected def A: Monoid[A]

  def empty: Resource[F, E, A] = Resource.pure(A.empty)
}

abstract private[effect] class ResourceSemigroup[F[_], E, A]
    extends Semigroup[Resource[F, E, A]] {
  implicit protected def F: Monad[F]
  implicit protected def A: Semigroup[A]

  def combine(rx: Resource[F, E, A], ry: Resource[F, E, A]): Resource[F, E, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

abstract private[effect] class ResourceSemigroupK[F[_], E]
    extends SemigroupK[Resource[F, E, *]] {
  implicit protected def F: Resource.Bracket[F, E]
  implicit protected def K: SemigroupK[F]
  implicit protected def G: Ref.Make[F]

  def combineK[A](ra: Resource[F, E, A], rb: Resource[F, E, A]): Resource[F, E, A] =
    Resource.make(Ref[F].of(F.unit))(_.get.flatten).evalMap { finalizers =>
      def allocate(r: Resource[F, E, A]): F[A] =
        r.fold(
          _.pure[F],
          (release: F[Unit]) => finalizers.update(Resource.Bracket[F, E].guarantee(_)(release)))

      K.combineK(allocate(ra), allocate(rb))
    }
}

abstract private[effect] class ResourceParCommutativeApplicative[F[_], E]
    extends CommutativeApplicative[Resource.Par[F, E, *]] {
  import Resource.Par
  import Resource.Par.{unwrap, apply => par}

  implicit protected def F0: GenConcurrent[F, E]

  final override def map[A, B](fa: Par[F, E, A])(f: A => B): Par[F, E, B] =
    par(unwrap(fa).map(f))
  final override def pure[A](x: A): Par[F, E, A] =
    par(Resource.pure[F, E, A](x))
  final override def product[A, B](
      fa: Par[F, E, A],
      fb: Par[F, E, B]): Par[F, E, (A, B)] =
    par(unwrap(fa).parZip(unwrap(fb)))
  final override def map2[A, B, Z](fa: Par[F, E, A], fb: Par[F, E, B])(
      f: (A, B) => Z): Par[F, E, Z] =
    map(product(fa, fb)) { case (a, b) => f(a, b) }
  final override def ap[A, B](ff: Par[F, E, A => B])(
      fa: Par[F, E, A]): Par[F, E, B] =
    map(product(ff, fa)) { case (ff, a) => ff(a) }
}

abstract private[effect] class ResourceParallel[F0[_], E] extends Parallel[Resource[F0, E, *]] {
  protected def F0: Applicative[Resource.Par[F0, E, *]]
  protected def F1: Monad[Resource[F0, E, *]]

  type F[x] = Resource.Par[F0, E, x]

  final override val applicative: Applicative[Resource.Par[F0, E, *]] = F0
  final override val monad: Monad[Resource[F0, E, *]] = F1

  final override val sequential: Resource.Par[F0, E, *] ~> Resource[F0, E, *] =
    new (Resource.Par[F0, E, *] ~> Resource[F0, E, *]) {
      def apply[A](fa: Resource.Par[F0, E, A]): Resource[F0, E, A] = Resource.Par.unwrap(fa)
    }

  final override val parallel: Resource[F0, E, *] ~> Resource.Par[F0, E, *] =
    new (Resource[F0, E, *] ~> Resource.Par[F0, E, *]) {
      def apply[A](fa: Resource[F0, E, A]): Resource.Par[F0, E, A] = Resource.Par(fa)
    }
}
