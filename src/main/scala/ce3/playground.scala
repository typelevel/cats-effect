/*
 * Copyright 2019 Daniel Spiewak
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

package ce3

import cats.{~>, Id, InjectK, Monad, Traverse}
import cats.data.{Const, EitherK, StateT}
import cats.implicits._

object playground {

  sealed trait StateF[S, A] extends Product with Serializable

  object StateF {
    final case class Get[S]() extends StateF[S, S]
    final case class Put[S](s: S) extends StateF[S, Unit]
  }

  object State {
    def get[F[_], S](implicit I: InjectK[StateF[S, ?], F]): Free[F, S] =
      Free.inject[StateF[S, ?], F](StateF.Get[S]())

    def put[F[_], S](s: S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      Free.inject[StateF[S, ?], F](StateF.Put[S](s))

    def modify[F[_], S](f: S => S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      modifyF[F, S](f.andThen(_.pure[Free[F, ?]]))

    def modifyF[F[_], S](f: S => Free[F, S])(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      get[F, S].flatMap(s => f(s).flatMap(put[F, S](_)))
  }

  final case class Never[A]()
  final case class Fork[F[_], A](body: F[_])

  type FiberId = Int
  val MainFiberId = 0

  type Results = Map[FiberId, Option[_]]   // None represents cancelation

  type PureConcurrentF[F[_], E, A] =
    EitherK[
      StateF[Results, ?],    // fiber results
      EitherK[
        StateF[FiberId, ?],       // current fiber id
        EitherK[
          Never,
          EitherK[
            Fork[F, ?],
            ExitCase[Id, E, ?],
            ?],
          ?],
        ?],
      A]

  // this is a class because we need the recursion for Fork
  final case class PureConcurrent[E, A](body: Free[PureConcurrent.F[E, ?], A])

  object PureConcurrent {
    type F[E, A] = PureConcurrentF[PureConcurrent[E, ?], E, A]
  }

  def ResultsI[E] = InjectK[StateF[Results, ?], PureConcurrent.F[E, ?]]
  def FiberIdI[E] = InjectK[StateF[FiberId, ?], PureConcurrent.F[E, ?]]
  def NeverI[E] = InjectK[Never, PureConcurrent.F[E, ?]]
  def ForkI[E] = InjectK[Fork[PureConcurrent[E, ?], ?], PureConcurrent.F[E, ?]]
  def ExitCaseI[E] = InjectK[ExitCase[Id, E, ?], PureConcurrent.F[E, ?]]

  def nextFiberId[E]: PureConcurrent[E, FiberId] =
    PureConcurrent(State.get[PureConcurrent.F[E, ?], FiberId])

  def run[E, A](pc: PureConcurrent[E, A]): ExitCase[Option, E, A] = ???

  // useful combinator for merging interpreters (should exist on EitherK, really)
  def merge[F[_], G[_], H[_]](f: F ~> H, g: G ~> H): EitherK[F, G, ?] ~> H =
    λ[EitherK[F, G, ?] ~> H](_.fold(f, g))

  /**
   * Implements a cooperative parallel interpreter for Free with a global state space,
   * given an injection for state and a traversable pattern functor. The state is
   * shared across all simultaneous suspensions and will survive short-circuiting
   * in the target monad (if relevant). Note that callers are responsible for
   * ensuring that any short circuiting in the target monad is handled prior to
   * suspending the actual fork, otherwise the short circuiting will propagate
   * through the entire system and terminate all suspensions.
   *
   * Forks are defined by a non-empty traversal on the suspension pattern functor
   * (excepting the state pattern). In most interpreter-like usage of Free, suspensions
   * are only trivially traversable, in that they contain no case which contains any
   * sub-structure. As an example, the StateF pattern functor has two cases, GetF and
   * PutF, neither of which contain an "A". Thus, while StateF does form a Traverse,
   * it is always empty and trivial. These patterns are considered to be "non-forking".
   *
   * Pattern instances which *do* contain one or more "A" values representing forks.
   * We evaluate these by taking each A "hole" and passing it to the continuation
   * *independently*. Which is to say, whenever you suspend a forking pattern instance,
   * any flatMap which succeeds it will be instantiated with *n + 1* different values:
   *
   * - Once for the value which is returned from the T ~> M interpreter
   * - Once for each value of type A within the fork
   *
   * The best way to think of this is that it is similar to the `fork()` function
   * in the C standard library, which returns different results whether you're in
   * the child or the parent. Suspending a forking pattern and then binding on it
   * will see different values depending on whether you're in the main fiber
   * (which will receive the value from the T ~> M interpreter) or in one of the
   * forked continuations (which will receive a corresponding value from the
   * suspension).
   *
   * "Daemon" semantics correspond to a mode in which forked suspensions with
   * outstanding continuations are ignored if the main suspension reaches
   * completion (either Pure or Suspend). This is analogous to how daemon threads
   * will not prevent the termination of the JVM, even if they still have outstanding
   * work when the main thread terminates. Non-daemon semantics will cause the
   * final result to be held pending the resolution of all forked suspensions.
   *
   * Sequence orders are deterministic, but not guaranteed to follow any particular
   * order. In other words, race conditions can and will happen, but if they resolve
   * in a particular direction in one run, they will resolve in the same direction
   * on the next run unless other changes are made. Changes to the structure of the
   * forked Free(s) (such as adding or removing an extra flatMap) can and will have
   * an impact on evaluation order, and thus race condition resolution.
   *
   * Fairness is not considered. If you care about fairness, you probably also care
   * about other little things like... uh... performance.
   */
  def forkFoldFree[S[_], T[_]: Traverse, U, A, M[_]: Monad](
      target: Free[S, A])(
      f: T ~> M,
      daemon: Boolean = true)(
      implicit PU: ProjectK[StateF[U, ?], S, T])
      : StateT[M, U, A] = {

    // NB: all the asInstanceOf usage here is safe and will be erased; it exists solely because scalac can't unify certain existentials

    // non-empty list of fibers where we know the type of the head and nothing else
    def runAll(universe: U, main: Free[S, A], fibers: List[Free[S, Unit]]): M[(U, A)] = {
      type StepLeft[X] = M[X]
      type StepRight[X] = (Free[S, X], List[Free[S, Unit]])

      type Step[X] = Either[StepLeft[X], StepRight[X]]

      val Nil = List[Free[S, Unit]]()   // cheating

      def stepFiber[X](universe: U, fiber: Free[S, X]): M[(U, Step[X])] = {
        fiber.foldStep(
          onPure = a => a.pure[M].asLeft[StepRight[X]].pure[M].tupleLeft(universe),

          onSuspend = { sa =>
            PU(sa) match {
              case Left(StateF.Get()) =>
                universe.asInstanceOf[X].pure[M].asLeft[StepRight[X]].pure[M].tupleLeft(universe)

              case Left(StateF.Put(universe)) =>
                ().asInstanceOf[X].pure[M].asLeft[StepRight[X]].pure[M].tupleLeft(universe)

              case Right(ta) =>
                // we can't possibly fork here since there are no continuations, so we just terminate
                f(ta).asLeft[StepRight[X]].pure[M].tupleLeft(universe)
            }
          },

          onFlatMapped = { pair =>
            val (front, cont) = pair

            PU(front) match {
              case Left(StateF.Get()) =>
                (cont(universe.asInstanceOf), Nil).asRight[StepLeft[X]].pure[M].tupleLeft(universe)

              case Left(StateF.Put(universe2)) =>
                (cont(().asInstanceOf), Nil).asRight[StepLeft[X]].pure[M].tupleLeft(universe2)

              case Right(front) =>
                val forks = front.toList.map(x => cont(x.asInstanceOf).void)
                val backM = f(front) map { x =>
                  (cont(x.asInstanceOf), forks).asRight[StepLeft[X]]
                }

                backM.tupleLeft(universe)
            }
          })
      }

      val daemonized = stepFiber(universe, main) flatMap { tuple =>
        tuple traverse {
          case Left(ma) =>
            // if non-daemon semantics and we still have outstanding fibers, transform back into a Right
            if (daemon || fibers.isEmpty)
              ma.asLeft[StepRight[A]].pure[M]
            else
              ma.map(a => (Free.pure[S, A](a), fibers).asRight[StepLeft[A]])

          case Right((main, forked)) =>
            (main, forked ::: fibers).asRight[StepLeft[A]].pure[M]
        }
      }

      daemonized flatMap {
        case (universe, Left(ma)) =>
          ma.tupleLeft(universe)

        case (universe, Right((main2, fibers))) =>
          // amusingly, we reverse the list with each step... that's technically okay
          val fibers2M = fibers.foldLeftM((universe, Nil)) {
            case ((universe, acc), fiber) =>
              stepFiber(universe, fiber) flatMap { tuple =>
                tuple traverse {
                  // fiber completed; drop it from the list
                  case Left(ma) =>
                    ma.as(acc)    // ensure we sequence the final action... and then drop the results

                  // fiber stepped, optionally forking
                  case Right((fiber2, forked2)) =>
                    (fiber2 :: forked2 ::: acc).pure[M]
                }
              }
          }

          fibers2M flatMap {
            case (universe, fibers2) =>
              runAll(universe, main2, fibers2)
          }
      }
    }

    StateT(runAll(_, target, Nil))
  }
}
