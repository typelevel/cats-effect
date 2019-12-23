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

import cats.{~>, Id, InjectK, Monad}
import cats.data.{Const, EitherK}
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

  type FiberId = Int
  val MainFiberId = 0

  type Cancelations = Set[FiberId]

  type PureConcurrentF[E, A] =
    EitherK[
      StateF[Cancelations, ?],    // canceled fibers
      EitherK[
        StateF[FiberId, ?],       // current fiber id
        EitherK[
          Never,
          ExitCase[Id, E, ?],
          ?],
        ?],
      A]

  type PureConcurrent[E, A] = Free[PureConcurrentF[E, ?], A]

  def CancelationsI[E] = InjectK[StateF[Cancelations, ?], PureConcurrentF[E, ?]]
  def FiberIdI[E] = InjectK[StateF[FiberId, ?], PureConcurrentF[E, ?]]
  def NeverI[E] = InjectK[Never, PureConcurrentF[E, ?]]
  def ExitCaseI[E] = InjectK[ExitCase[Id, E, ?], PureConcurrentF[E, ?]]

  def nextFiberId[E]: PureConcurrent[E, FiberId] =
    State.get[PureConcurrentF[E, ?], FiberId]

  def forkFoldFree[S[_], A, M[_]: Monad](
      target: Free[S, A])(
      f: S ~> EitherK[(Free[S, _], ?), M, ?],
      daemon: Boolean = false)
      : M[A] = {

    import cats.implicits._

    // non-empty list of fibers where we know the type of the head and nothing else
    def runAll(main: Free[S, A], fibers: List[Free[S, _]]): M[A] = {
      type StepLeft[X] = M[X]
      type StepRight[X] = (Free[S, X], Option[Free[S, _]])
      type StepRightList[X] = (Free[S, X], List[Free[S, _]])

      type Step[X] = Either[StepLeft[X], StepRight[X]]

      def stepFiber[X](fiber: Free[S, X]): M[Step[X]] = {
        fiber.foldStep(
          onPure = a => a.pure[M].asLeft[StepRight[X]].pure[M],

          onSuspend = { sa =>
            f(sa).run match {
              case Left((fiber, a)) =>
                (Free.pure[S, X](a), Some(fiber): Option[Free[S, _]]).asRight[StepLeft[X]].pure[M]

              case Right(ma) =>
                ma.asLeft[StepRight[X]].pure[M]
            }
          },

          onFlatMapped = { pair =>
            val (front, cont) = pair

            // the asInstanceOf usage here is getting around a bug in scalac, where it simply isn't unifying the existential with itself
            f(front).run match {
              case Left((fiber, x)) =>
                (cont(x.asInstanceOf), Some(fiber): Option[Free[S, _]]).asRight[StepLeft[X]].pure[M]

              case Right(mx) =>
                mx.map(x => (cont(x.asInstanceOf), None: Option[Free[S, _]]).asRight[StepLeft[X]])
            }
          })
      }

      val daemonized = stepFiber(main) flatMap {
        case Left(ma) =>
          // if non-daemon semantics and we still have outstanding fibers, transform back into a Right
          if (daemon || fibers.isEmpty)
            ma.asLeft[StepRightList[A]].pure[M]
          else
            ma.map(a => (Free.pure[S, A](a), fibers).asRight[StepLeft[A]])

        case Right((main, forked)) =>
          (main, forked.map(_ :: fibers).getOrElse(fibers)).asRight[StepLeft[A]].pure[M]
      }

      daemonized flatMap {
        case Left(ma) =>
          ma

        case Right((main2, fibers)) =>
          val fibers2M = fibers traverse { fiber =>
            stepFiber(fiber) flatMap {
              // fiber completed; drop it from the list
              case Left(ma) =>
                ma.as(Nil: List[Free[S, _]])    // ensure we sequence the final action... and then drop the results

              // fiber stepped, optionally forking
              case Right((fiber2, forked2)) =>
                (fiber2 :: forked2.map(_ :: Nil).getOrElse(Nil): List[Free[S, _]]).pure[M]
            }
          }

          fibers2M flatMap { fibers2 =>
            runAll(main2, fibers2.flatten)
          }
      }
    }

    runAll(target, Nil)
  }
}
