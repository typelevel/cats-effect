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
import cats.syntax.all._
import cats.effect.kernel.implicits._

import scala.annotation.tailrec
import cats.data.Kleisli

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
 * Usage is done via [[Resource!.use use]] and note that resource usage nests.
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
 * Further node types are used internally. To compile a resource down to the
 * above three types, call [[Resource#preinterpret]].
 *
 * @tparam F the effect type in which the resource is allocated and released
 * @tparam A the type of resource
 */
sealed abstract class Resource[F[_], +A] {
  import Resource._

  private[effect] def fold[B](
      onOutput: A => F[B],
      onRelease: F[Unit] => F[Unit]
  )(implicit F: MonadCancel[F, Throwable]): F[B] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[A]
    final case class Frame[AA, BB](head: AA => Resource[F, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](current: Resource[F, C], stack: Stack[C]): F[B] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](current: Resource[F, C], stack: Stack[C]): F[B] =
      current match {
        case Allocate(resource) =>
          F.bracketFull(resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput(a)
                case Frame(head, tail) => continue(head(a), tail)
              }
          } {
            case ((_, release), outcome) =>
              onRelease(release(ExitCase.fromOutcome(outcome)))
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack))
        case Pure(v) =>
          stack match {
            case Nil => onOutput(v)
            case Frame(head, tail) =>
              loop(head(v), tail)
          }
        case Eval(fa)  =>
          fa.flatMap(a => continue(Resource.pure(a), stack))
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
  def use[B](f: A => F[B])(implicit F: MonadCancel[F, Throwable]): F[B] =
    fold(f, identity)

  /**
   * Allocates a resource with a non-terminating use action.
   * Useful to run programs that are expressed entirely in `Resource`.
   *
   * The finalisers run when the resulting program fails or gets interrupted.
   */
  def useForever(implicit F: Spawn[F]): F[Nothing] =
    use[Nothing](_ => F.never)

  /**
   * Allocates a resource and closes it immediately.
   */
  def use_(implicit F: MonadCancel[F, Throwable]): F[Unit] = use(_ => F.unit)

  /**
   * Allocates the resource and uses it to run the given Kleisli.
   */
  def useKleisli[B >: A, C](usage: Kleisli[F, B, C])(
      implicit F: MonadCancel[F, Throwable]): F[C] =
    use(usage.run)

  /**
   * Creates a FunctionK that, when applied, will allocate the resource and use it to run the given Kleisli.
   */
  def useKleisliK[B >: A](
      implicit F: MonadCancel[F, Throwable]): Kleisli[F, B, *] ~> F =
    new (Kleisli[F, B, *] ~> F) {
      def apply[C](fa: Kleisli[F, B, C]): F[C] = useKleisli(fa)
    }

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
  def parZip[B](
      that: Resource[F, B]
  )(implicit F: Concurrent[F]): Resource[F, (A, B)] = {
    type Update = (F[Unit] => F[Unit]) => F[Unit]

    def allocate[C](r: Resource[F, C], storeFinalizer: Update): F[C] =
      r.fold(
        _.pure[F],
        release => storeFinalizer(MonadCancel[F, Throwable].guarantee(_, release))
      )

    val bothFinalizers = Ref.of(().pure[F] -> ().pure[F])

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
  def flatMap[B](f: A => Resource[F, B]): Resource[F, B] =
    Bind(this, f)

  /**
   *  Given a mapping function, transforms the resource provided by
   *  this Resource.
   *
   *  This is the standard `Functor.map`.
   */
  def map[B](f: A => B): Resource[F, B] =
    flatMap(a => Resource.pure[F, B](f(a)))

  /**
   * Given a natural transformation from `F` to `G`, transforms this
   * Resource from effect `F` to effect `G`.
   */
  def mapK[G[_]](
      f: F ~> G
  )(implicit F: MonadCancelThrow[F], G: MonadCancelThrow[G]): Resource[G, A] =
    this match {
      case Allocate(resource) =>
        // TODO replace with applyFull
        Allocate[G, A] { (gpoll: Poll[G]) =>
            gpoll {
              f {
                F.uncancelable { (fpoll: Poll[F]) =>
                  resource(fpoll)
                }
              }
            }.map { case (a, release) =>
                a -> ((r: ExitCase) => f(release(r)))
            }
        }
      case Bind(source, f0) =>
        // we insert a bind to get stack safety
        suspend(G.unit >> source.mapK(f).pure[G])
          .flatMap(x => f0(x).mapK(f))
      case Pure(a) =>
        Resource.pure(a)
      case Eval(fea) => Resource.eval(f(fea))
    }

  /**
   * Runs `precede` before this resource is allocated.
   */
  def preAllocate(precede: F[Unit]): Resource[F, A] =
    Resource.eval(precede).flatMap(_ => this)

  /**
   * Runs `finalizer` when this resource is closed. Unlike the release action passed to `Resource.make`, this will
   * run even if resource acquisition fails or is canceled.
   */
  def onFinalize(finalizer: F[Unit])(implicit F: Applicative[F]): Resource[F, A] =
    onFinalizeCase(_ => finalizer)

  /**
   * Like `onFinalize`, but the action performed depends on the exit case.
   */
  def onFinalizeCase(f: ExitCase => F[Unit])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.makeCase(F.unit)((_, ec) => f(ec)).flatMap(_ => this)

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
  def allocated[B >: A](
      implicit F: MonadCancel[F, Throwable]): F[(B, F[Unit])] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[B]
    final case class Frame[AA, BB](head: AA => Resource[F, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](
        current: Resource[F, C],
        stack: Stack[C],
        release: F[Unit]): F[(B, F[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](
        current: Resource[F, C],
        stack: Stack[C],
        release: F[Unit]): F[(B, F[Unit])] =
      current match {
        case Allocate(resource) =>
          F.bracketFull(resource) {
            case (b, rel) =>
              stack match {
                case Nil =>
                  (
                    b: B,
                    rel(ExitCase.Succeeded).guarantee(release)
                  ).pure[F]
                case Frame(head, tail) =>
                  continue(head(b), tail, rel(ExitCase.Succeeded).guarantee(release))
              }
          } {
            case (_, Outcome.Succeeded(_)) =>
              F.unit
            case ((_, release), outcome) =>
              release(ExitCase.fromOutcome(outcome))
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack), release)
        case Pure(v) =>
          stack match {
            case Nil =>
              (v: B, release).pure[F]
            case Frame(head, tail) =>
              loop(head(v), tail, release)
          }
        case Eval(fa)  =>
          fa.flatMap(a => continue(Resource.pure(a), stack, release))
      }

    loop(this, Nil, F.unit)
  }

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatMap` on `F[A]` while maintaining the resource context
   */
  def evalMap[B](f: A => F[B]): Resource[F, B] =
    this.flatMap(a => Resource.eval(f(a)))

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatTap` on `F[A]` while maintaining the resource context
   */
  def evalTap[B](f: A => F[B]): Resource[F, A] =
    this.flatMap(a => Resource.eval(f(a)).map(_ => a))

  // /**
  //  * Widens the effect type of this resource.
  //  */
  // def covary[F[x] >: F[x]]: Resource[F, A] = this

  /**
   * Acquires the resource, runs `gb` and closes the resource once `gb` terminates, fails or gets interrupted
   */
  def surround[B](gb: F[B])(implicit F: MonadCancel[F, Throwable]): F[B] =
    use(_ => gb)

  /**
   * Creates a FunctionK that can run `gb` within a resource, which is then closed once `gb` terminates, fails or gets interrupted
   */
  def surroundK(implicit F: MonadCancel[F, Throwable]): F ~> F =
    new (F ~> F) {
      override def apply[B](gb: F[B]): F[B] = surround(gb)
    }
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
    applyCase[F, A] {
      resource.map {
        case (a, release) =>
          (a, (_: ExitCase) => release)
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
  def applyCase[F[_], A](resource: F[(A, ExitCase => F[Unit])]): Resource[F, A] =
    Allocate((_: Poll[F]) => resource)

  /**
   * Creates a resource from an allocating effect, with a finalizer
   * that is able to distinguish between [[ExitCase exit cases]].
   *
   * The action takes a `Poll[F]` to allow for interruptible acquires,
   * which is most often useful when acquiring lock-like structure: it
   * should be possible to interrupt a fiber waiting on a lock, but if
   * it does get acquired, release need to be guaranteed.
   *
   * Note that in this case the acquire action should know how to cleanup
   * after itself in case it gets canceled, since Resource will only
   * guarantee release when acquire succeeds and fails (and when the
   * actions in `use` or `flatMap` fail, succeed, or get canceled)
   *
   * TODO make sure this api, which is more general than makeFull, doesn't allow
   *      for interruptible releases
   *
   *
   * @see [[makeFull]] for a version that separates the needed resource
   *      with its finalizer tuple in two parameters
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param resource an effect that returns a tuple of a resource and
   *        an effectful function to release it, where acquisition can
   *        potentially be interrupted
   */
  def applyFull[F[_], A](resource:  Poll[F] => F[(A, ExitCase => F[Unit])]): Resource[F, A] =
    Allocate(resource)

  /**
   * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
   */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.eval(fr).flatMap(x => x)

  /**
   * Creates a resource from an acquiring effect and a release function.
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire an effect to acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(
      implicit F: Functor[F]): Resource[F, A] =
    apply[F, A](acquire.map(a => a -> release(a)))

  /**
   * Creates a resource from an acquiring effect and a release function that can
   * discriminate between different [[ExitCase exit cases]].
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire a function to effectfully acquire a resource
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def makeCase[F[_], A](
    acquire: F[A]
  )(release: (A, ExitCase) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, e => release(a, e))))


  /**
   * Creates a resource from an acquiring effect and a release
   * function that can discriminate between different [[ExitCase exit
   * cases]].
   *
   * The acquiring effect takes a `Poll[F]` to allow for interruptible
   * acquires, which is most often useful when acquiring lock-like
   * structures: it should be possible to interrupt a fiber waiting on
   * a lock, but if it does get acquired, release need to be
   * guaranteed.
   *
   * Note that in this case the acquire action should know how to cleanup
   * after itself in case it gets canceled, since Resource will only
   * guarantee release when acquire succeeds and fails (and when the
   * actions in `use` or `flatMap` fail, succeed, or get canceled)
   *
   * @tparam F the effect type in which the resource is acquired and released
   * @tparam A the type of the resource
   * @param acquire an effect to acquire a resource, possibly interruptibly
   * @param release a function to effectfully release the resource returned by `acquire`
   */
  def makeFull[F[_], A](acquire: Poll[F] => F[A])(release: (A, ExitCase) => F[Unit])(
    implicit F: Functor[F]): Resource[F, A] =
    applyFull[F, A](poll => acquire(poll).map(a => (a, e => release(a, e))))

  /**
   * Lifts a pure value into a resource. The resource has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], A](a: A): Resource[F, A] =
    Pure(a)

  /**
   * A resource with a no-op allocation and a no-op release.
   */
  def unit[F[_]]: Resource[F, Unit] = pure(())

  /**
   * Lifts an applicative into a resource. The resource has a no-op release.
   * Preserves interruptibility of `fa`.
   *
   * @param fa the value to lift into a resource
   */
  @deprecated("please use `eval` instead.")
  def liftF[F[_], A](fa: F[A]): Resource[F, A] =
    Resource.Eval(fa)

  def eval[F[_], A](fa: F[A]): Resource[F, A] =
    Resource.Eval(fa)

  /**
   * Lifts a finalizer into a resource. The resource has a no-op allocation.
   */
  def onFinalize[F[_]: Applicative](release: F[Unit]): Resource[F, Unit] =
    unit.onFinalize(release)

  /**
   * Creates a resource that allocates immediately without any effects,
   * but calls `release` when closing, providing the [[ExitCase the usage completed with]].
   */
  def onFinalizeCase[F[_]: Applicative](release: ExitCase => F[Unit]): Resource[F, Unit] =
    unit.onFinalizeCase(release)

  /**
   * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
   */
  def liftK[F[_]]: F ~> Resource[F, *] =
    new (F ~> Resource[F, *]) {
      def apply[A](fa: F[A]): Resource[F, A] = Resource.eval(fa)
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
   *   def reader[F[_]](data: String)(implicit F: Sync[F]): Resource[F, Source] =
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
      implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.blocking(autoCloseable.close()))

  /**
   * `Resource` data constructor that wraps an effect allocating a resource,
   * along with its finalizers.
   */
  final case class Allocate[F[_], A](resource: Poll[F] => F[(A, ExitCase => F[Unit])])
      extends Resource[F, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, +A](source: Resource[F, S], fs: S => Resource[F, A])
      extends Resource[F, A]

  final case class Pure[F[_], +A](a: A) extends Resource[F, A]

  final case class Eval[F[_], A](fa: F[A]) extends Resource[F, A]


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
  sealed trait ExitCase extends Product with Serializable
  object ExitCase {

    /**
     * An [[ExitCase]] that signals successful completion.
     *
     * Note that "successful" is from the type of view of the
     * `MonadCancel` type.
     *
     * When combining such a type with `EitherT` or `OptionT` for
     * example, this exit condition might not signal a successful
     * outcome for the user, but it does for the purposes of the
     * `bracket` operation. <-- TODO still true?
     */
    case object Succeeded extends ExitCase

    /**
     * An [[ExitCase]] signaling completion in failure.
     */
    final case class Errored(e: Throwable) extends ExitCase

    /**
     * An [[ExitCase]] signaling that the action was aborted.
     *
     * As an example this can happen when we have a cancelable data type,
     * like [[IO]] and the task yielded by `bracket` gets canceled
     * when it's at its `use` phase.
     */
    case object Canceled extends ExitCase

    def fromOutcome[F[_], A](outcome: Outcome[F, Throwable, A]): ExitCase =
      outcome match {
        case Outcome.Succeeded(_) => Succeeded
        case Outcome.Errored(t) => Errored(t)
        case Outcome.Canceled() => Canceled
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
  type Par[F[_], +A] = Par.Type[F, A]

  object Par {
    type Base
    trait Tag extends Any
    type Type[F[_], +A] <: Base with Tag

    def apply[F[_], A](fa: Resource[F, A]): Type[F, A] =
      fa.asInstanceOf[Type[F, A]]

    def unwrap[F[_], A](fa: Type[F, A]): Resource[F, A] =
      fa.asInstanceOf[Resource[F, A]]
  }
}

abstract private[effect] class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectMonadErrorForResource[F[_], E](
      implicit F0: MonadError[F, E]): MonadError[Resource[F, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A](
      implicit F0: Monad[F],
      A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectCommutativeApplicativeForResourcePar[F[_]](
      implicit F: Concurrent[F]
  ): CommutativeApplicative[Resource.Par[F, *]] =
    new ResourceParCommutativeApplicative[F] {
      def F0 = F
    }

  implicit def catsEffectParallelForResource[F0[_]: Concurrent]
      : Parallel.Aux[Resource[F0, *], Resource.Par[F0, *]] =
    new ResourceParallel[F0] {
      def F0 = catsEffectCommutativeApplicativeForResourcePar
      def F1 = catsEffectMonadForResource
    }
}

abstract private[effect] class ResourceInstances0 {
  implicit def catsEffectMonadForResource[F[_]](implicit F0: Monad[F]): Monad[Resource[F, *]] =
    new ResourceMonad[F] {
      def F = F0
    }

  implicit def catsEffectSemigroupForResource[F[_], A](
      implicit F0: Monad[F],
      A0: Semigroup[A]): ResourceSemigroup[F, A] =
    new ResourceSemigroup[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](
      implicit F0: MonadCancel[F, Throwable],
      K0: SemigroupK[F],
      G0: Ref.Make[F]): ResourceSemigroupK[F] =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
      def G = G0
    }
}

abstract private[effect] class ResourceMonadError[F[_], E]
    extends ResourceMonad[F]
    with MonadError[Resource[F, *], E] {
  import Resource._

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa match {
      case Allocate(resource) =>
        // TODO replace with applyFull
        Allocate { (poll: Poll[F]) =>
          resource(poll).attempt.map {
            case Left(error) => (Left(error), (_: ExitCase) => F.unit)
            case Right((a, release)) => (Right(a), release)
          }
        }
      case Bind(source, f) =>
        source.attempt.flatMap {
          case Left(error) => Resource.pure(error.asLeft)
          case Right(s) => f(s).attempt
        }
      case p@ Pure(_) =>
        Resource.pure(p.a.asRight)
      case e@ Eval(_) =>
        Resource.eval(e.fa.attempt)
    }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    attempt(fa).flatMap {
      case Right(a) => Resource.pure(a)
      case Left(e) => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource.eval(F.raiseError[A](e))
}

abstract private[effect] class ResourceMonad[F[_]] extends Monad[Resource[F, *]] with StackSafeMonad[Resource[F, *]] {

  implicit protected def F: Monad[F]

  def pure[A](a: A): Resource[F, A] =
    Resource.pure(a)

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)
}

abstract private[effect] class ResourceMonoid[F[_], A]
    extends ResourceSemigroup[F, A]
    with Monoid[Resource[F, A]] {
  implicit protected def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure[F, A](A.empty)
}

abstract private[effect] class ResourceSemigroup[F[_], A] extends Semigroup[Resource[F, A]] {
  implicit protected def F: Monad[F]
  implicit protected def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

abstract private[effect] class ResourceSemigroupK[F[_]] extends SemigroupK[Resource[F, *]] {
  implicit protected def F: MonadCancel[F, Throwable]
  implicit protected def K: SemigroupK[F]
  implicit protected def G: Ref.Make[F]

  def combineK[A](ra: Resource[F, A], rb: Resource[F, A]): Resource[F, A] =
    Resource.make(Ref[F].of(F.unit))(_.get.flatten).evalMap { finalizers =>
      def allocate(r: Resource[F, A]): F[A] =
        r.fold(
          _.pure[F],
          (release: F[Unit]) =>
            finalizers.update(MonadCancel[F, Throwable].guarantee(_, release)))

      K.combineK(allocate(ra), allocate(rb))
    }
}

abstract private[effect] class ResourceParCommutativeApplicative[F[_]]
    extends CommutativeApplicative[Resource.Par[F, *]] {
  import Resource.Par
  import Resource.Par.{unwrap, apply => par}

  implicit protected def F0: Concurrent[F]

  final override def map[A, B](fa: Par[F, A])(f: A => B): Par[F, B] =
    par(unwrap(fa).map(f))
  final override def pure[A](x: A): Par[F, A] =
    par(Resource.pure[F, A](x))
  final override def product[A, B](fa: Par[F, A], fb: Par[F, B]): Par[F, (A, B)] =
    par(unwrap(fa).parZip(unwrap(fb)))
  final override def map2[A, B, Z](fa: Par[F, A], fb: Par[F, B])(f: (A, B) => Z): Par[F, Z] =
    map(product(fa, fb)) { case (a, b) => f(a, b) }
  final override def ap[A, B](ff: Par[F, A => B])(fa: Par[F, A]): Par[F, B] =
    map(product(ff, fa)) { case (ff, a) => ff(a) }
}

abstract private[effect] class ResourceParallel[F0[_]] extends Parallel[Resource[F0, *]] {
  protected def F0: Applicative[Resource.Par[F0, *]]
  protected def F1: Monad[Resource[F0, *]]

  type F[x] = Resource.Par[F0, x]

  final override val applicative: Applicative[Resource.Par[F0, *]] = F0
  final override val monad: Monad[Resource[F0, *]] = F1

  final override val sequential: Resource.Par[F0, *] ~> Resource[F0, *] =
    new (Resource.Par[F0, *] ~> Resource[F0, *]) {
      def apply[A](fa: Resource.Par[F0, A]): Resource[F0, A] = Resource.Par.unwrap(fa)
    }

  final override val parallel: Resource[F0, *] ~> Resource.Par[F0, *] =
    new (Resource[F0, *] ~> Resource.Par[F0, *]) {
      def apply[A](fa: Resource[F0, A]): Resource.Par[F0, A] = Resource.Par(fa)
    }
}
