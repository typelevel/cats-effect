/*
 * Copyright 2020-2025 Typelevel
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
package laws

import cats.{Eq, Order}
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}
import cats.effect.kernel.testkit.TimeT.{eqTimeT => _, orderTimeT => _, _}
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary._

import org.scalacheck.Prop

import scala.concurrent.duration._

import munit.DisciplineSuite

private[laws] trait PureConcSuiteLowPriorityTimeTInstances {
  implicit def orderTimeTPureConcFiniteDuration(
      implicit FA: Order[PureConc[Int, FiniteDuration]])
      : Order[TimeT[PureConc[Int, *], FiniteDuration]] =
    TimeT.orderTimeT
}

class PureConcSuite
    extends DisciplineSuite
    with BaseSuite
    with PureConcSuiteLowPriorityTimeTInstances {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  implicit def eqTimeTPureConc[A](
      implicit FA: Eq[PureConc[Int, A]]): Eq[TimeT[PureConc[Int, *], A]] =
    TimeT.eqTimeT

  {
    import cats.effect.kernel.{GenConcurrent, Outcome}
    import cats.effect.kernel.implicits._
    import cats.syntax.all._

    type F[A] = PureConc[Int, A]
    val F = GenConcurrent[F]

    test("short-circuit on error") {
      assert(
        pure.run((F.never[Unit], F.raiseError[Unit](42)).parTupled) === Outcome.Errored(42))
      assertEquals(
        pure.run((F.raiseError[Unit](42), F.never[Unit]).parTupled),
        Outcome.Errored[Option, Int, (Unit, Unit)](42))
    }

    test("short-circuit on canceled") {
      assertEquals(pure.run(F.canceled), Outcome.Canceled[Option, Int, Unit]())
      assertEquals(
        pure.run((F.never[Unit], F.canceled).parTupled),
        Outcome.Canceled[Option, Int, (Unit, Unit)]())
      assert(
        pure.run((F.never[Unit], F.canceled).parTupled.start.flatMap(_.join)) === Outcome
          .Succeeded(Some(Outcome.canceled[F, Int, (Unit, Unit)])))
      assert(
        pure.run((F.canceled, F.never[Unit]).parTupled.start.flatMap(_.join)) === Outcome
          .Succeeded(Some(Outcome.canceled[F, Int, (Unit, Unit)])))
    }

    test("not run forever on chained product") {
      import cats.effect.kernel.Par.ParallelF

      val fa: F[String] = F.pure("a")
      val fb: F[String] = F.pure("b")
      val fc: F[Unit] = F.raiseError[Unit](42)
      assert(
        pure.run(ParallelF.value(
          ParallelF(fa).product(ParallelF(fb)).product(ParallelF(fc)))) === Outcome.Errored(42))
    }

    test("ignore unmasking in finalizers") {
      val fa = F.uncancelable { poll => F.onCancel(poll(F.unit), poll(F.unit)) }

      pure.run(fa.start.flatMap(_.cancel))
    }
  }

  {
    import cats.effect.kernel.{GenConcurrent, GenTemporal, Outcome}
    import cats.effect.kernel.implicits._
    import cats.syntax.all._

    type F[A] = PureConc[Int, A]
    val F = GenConcurrent[F]

    test("run finalizers when canceling never") {
      val t = for {
        c <- F.ref(0)
        latch <- F.deferred[Unit]
        fib <- F.start((latch.complete(()) *> F.never[Unit]).onCancel(c.update(_ + 1)))
        _ <- latch.get
        _ <- fib.cancel
        v <- c.get
      } yield v

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("run finalizers when canceling Deferred#get") {
      val t = for {
        c <- F.ref(0)
        latch <- F.deferred[Unit]
        hang <- F.deferred[Unit]
        fib <- F.start((latch.complete(()) *> hang.get).onCancel(c.update(_ + 1)))
        _ <- latch.get
        _ <- fib.cancel
        v <- c.get
      } yield v

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("run finalizers when canceling Fiber#join") {
      val t = for {
        c <- F.ref(0)
        latch <- F.deferred[Unit]
        hang <- F.start(F.never[Unit])
        fib <- F.start((latch.complete(()) *> hang.join).onCancel(c.update(_ + 1)))
        _ <- latch.get
        _ <- fib.cancel
        v <- c.get
      } yield v

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("hang when canceling uncancelable never") {
      val t = for {
        latch <- F.deferred[Unit]
        f <- F.start((latch.complete(()) *> F.never[Unit]).uncancelable)
        _ <- latch.get
        _ <- f.cancel
      } yield ()

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("hang when canceling uncancelable Deferred#get") {
      val t = for {
        latch <- F.deferred[Unit]
        hang <- F.deferred[Unit]
        f <- F.start((latch.complete(()) *> hang.get).uncancelable)
        _ <- latch.get
        _ <- f.cancel
      } yield ()

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("hang when canceling uncancelable Fiber#join") {
      val t = for {
        latch <- F.deferred[Unit]
        hang <- F.start(F.never[Unit])
        f <- F.start((latch.complete(()) *> hang.join).uncancelable)
        _ <- latch.get
        _ <- f.cancel
      } yield ()

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("hang when canceling fiber blocked on cancel finalization") {
      val t = for {
        targetStarted <- F.deferred[Unit]
        finalizerStarted <- F.deferred[Unit]
        target <- F.start(
          (targetStarted.complete(()) *> F.never[Unit])
            .onCancel(finalizerStarted.complete(()) *> F.never[Unit]))
        _ <- targetStarted.get
        canceler <- F.start(target.cancel)
        _ <- finalizerStarted.get
        _ <- canceler.cancel
      } yield ()

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("run finalizers in order") {
      val t = for {
        results <- F.ref[String]("")
        f <- F start {
          F.canceled.onCancel(results.update(_ + "A")).onCancel(results.update(_ + "B"))
        }
        _ <- f.join
        back <- results.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, String](Some("AB")))
    }

    test("ignore cancelation of a fiber after racePair has completed") {
      val t = for {
        finalized <- F.ref(0)
        fiber <- F.start {
          F.racePair(F.unit, F.never[Unit]).void.onCancel(finalized.update(_ + 1))
        }
        _ <- fiber.join
        _ <- fiber.cancel
        _ <- F.cede
        back <- finalized.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(0)))
    }

    test("ignore cancelation of a fiber after race has completed") {
      val t = for {
        finalized <- F.ref(0)
        fiber <- F.start {
          F.race(F.unit, F.never[Unit]).void.onCancel(finalized.update(_ + 1))
        }
        _ <- fiber.join
        _ <- fiber.cancel
        _ <- F.cede
        back <- finalized.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(0)))
    }

    test("correctly interpret uncancelable cancelation followed by suspension") {
      val t = F.uncancelable(_ => F.canceled *> F.never[Unit])
      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))

      val forked = pure.run(F.start(t).flatMap(_.joinWith(F.canceled *> F.never[Unit])))
      assertEquals(forked, Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("observe pending cancelation before a pure polled action") {
      val t = F.uncancelable(poll => F.canceled *> poll(F.unit))

      assertEquals(pure.run(t), Outcome.Canceled[Option, Int, Unit]())
    }

    test("observe pending cancelation before an error handler") {
      val t = for {
        handlerRan <- F.ref(false)
        finalizerCount <- F.ref(0)
        fiber <- F.start {
          F.onCancel(
            F.handleErrorWith(F.uncancelable(_ => F.canceled *> F.raiseError[Unit](1)))(_ =>
              handlerRan.set(true)),
            finalizerCount.update(_ + 1))
        }
        outcome <- fiber.join
        handled <- handlerRan.get
        finalized <- finalizerCount.get
      } yield (outcome === Outcome.canceled[F, Int, Unit], handled, finalized)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Boolean, Boolean, Int)](Some((true, false, 1))))
    }

    test("observe pending cancelation before the next tailRecM iteration") {
      val t = for {
        iterationRan <- F.ref(false)
        finalizerCount <- F.ref(0)
        fiber <- F.start {
          F.onCancel(
            F.tailRecM[Int, Unit](0) {
              case 0 =>
                F.uncancelable(_ => F.canceled.as(Left(1): Either[Int, Unit]))
              case _ =>
                iterationRan.set(true).as(Right(()): Either[Int, Unit])
            },
            finalizerCount.update(_ + 1)
          )
        }
        outcome <- fiber.join
        iterated <- iterationRan.get
        finalized <- finalizerCount.get
      } yield (outcome === Outcome.canceled[F, Int, Unit], iterated, finalized)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Boolean, Boolean, Int)](Some((true, false, 1))))
    }

    test("ignore poll from another fiber") {
      val t = for {
        started <- F.deferred[Unit]
        polled <- F.deferred[Unit]

        parent <- F.start {
          F.uncancelable { poll =>
            started.complete(()) *>
              F.start(poll(polled.complete(()) *> F.never[Unit])).void *>
              polled.get *>
              F.never[Unit]
          }
        }

        _ <- started.get
        _ <- polled.get
        _ <- parent.cancel
      } yield ()

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("observe external cancelation while blocked inside poll") {
      val t = for {
        started <- F.deferred[Unit]
        polled <- F.deferred[Unit]
        gate <- F.deferred[Unit]
        ran <- F.ref(false)
        fiber <- F.start {
          F.uncancelable { poll =>
            started.complete(()) *>
              poll(polled.complete(()) *> gate.get *> ran.set(true))
          }
        }
        canceler <- F.start(polled.get *> fiber.cancel)
        _ <- started.get
        _ <- canceler.join
        back <- ran.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Boolean](Some(false)))
    }

    test("select current finalizers for external cancelation inside poll") {
      val t = for {
        polled <- F.deferred[Unit]
        gate <- F.deferred[Unit]
        finalized <- F.ref(0)
        fiber <- F.start {
          F.uncancelable { poll =>
            poll(F.onCancel(polled.complete(()) *> gate.get, finalized.update(_ + 1)))
          }
        }
        _ <- polled.get
        _ <- fiber.cancel
        back <- finalized.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("allow masked completion after unregistering external cancelation finalizers") {
      val t = for {
        masked <- F.deferred[Unit]
        gate <- F.deferred[Unit]
        finalized <- F.ref(0)
        fiber <- F.start {
          F.onCancel(
            F.uncancelable(_ => masked.complete(()) *> gate.get),
            finalized.update(_ + 1))
        }
        _ <- masked.get
        releaser <- F.start(F.cede *> gate.complete(()))
        _ <- fiber.cancel
        _ <- releaser.join
        outcome <- fiber.join
        back <- finalized.get
      } yield (outcome.isSuccess, back)

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, (Boolean, Int)](Some((true, 0))))
    }

    test("run finalizers around a self-canceling polled region") {
      val t = for {
        finalized <- F.ref(0)
        fiber <- F.start {
          F.uncancelable { poll => F.onCancel(poll(F.canceled), finalized.update(_ + 1)) }
        }
        _ <- fiber.join
        back <- finalized.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("run outer finalizers around a self-canceling polled region") {
      val t = for {
        finalized <- F.ref(0)
        fiber <- F.start {
          F.onCancel(F.uncancelable { poll => poll(F.canceled) }, finalized.update(_ + 1))
        }
        _ <- fiber.join
        back <- finalized.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Int](Some(1)))
    }

    test("skip outer finalizers when a masked self-cancel reaches the fiber terminus") {
      val t = for {
        finalized <- F.ref(0)
        fiber <- F.start {
          F.onCancel(
            F.uncancelable { poll => poll(F.uncancelable(_ => F.canceled)) },
            finalized.update(_ + 1))
        }
        outcome <- fiber.join
        back <- finalized.get
      } yield (outcome.isSuccess, back)

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, (Boolean, Int)](Some((true, 0))))
    }

    test("observe pending self-cancel before running a polled region") {
      val t = for {
        finalized <- F.ref(0)
        ran <- F.ref(false)
        fiber <- F.start {
          F.uncancelable { poll =>
            F.canceled *> F.onCancel(poll(ran.set(true)), finalized.update(_ + 1))
          }
        }
        _ <- fiber.join
        fin <- finalized.get
        body <- ran.get
      } yield (fin, body)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Int, Boolean)](Some((1, false))))
    }

    test("run only finalizers installed after a masked self-cancel") {
      val t = for {
        before <- F.ref(0)
        after <- F.ref(0)
        ran <- F.ref(false)
        fiber <- F.start {
          F.uncancelable { poll =>
            F.onCancel(F.canceled, before.update(_ + 1)) *>
              F.onCancel(poll(ran.set(true)), after.update(_ + 1))
          }
        }
        _ <- fiber.join
        beforeCount <- before.get
        afterCount <- after.get
        body <- ran.get
      } yield (beforeCount, afterCount, body)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Int, Int, Boolean)](Some((0, 1, false))))
    }

    test("skip active poll finalizers when a masked self-cancel reaches the fiber terminus") {
      val t = for {
        finalized <- F.ref("")
        fiber <- F.start {
          F.uncancelable { outerPoll =>
            F.onCancel(
              outerPoll {
                F.uncancelable { innerPoll =>
                  F.onCancel(
                    innerPoll(F.uncancelable(_ => F.canceled)),
                    finalized.update(_ + "B"))
                }
              },
              finalized.update(_ + "A"))
          }
        }
        outcome <- fiber.join
        back <- finalized.get
      } yield (outcome.isSuccess, back)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Boolean, String)](Some((true, ""))))
    }

    test("skip restored poll finalizers when a masked self-cancel reaches the fiber terminus") {
      val t = for {
        outerFinalized <- F.ref(0)
        innerFinalized <- F.ref(0)
        fiber <- F.start {
          F.uncancelable { outerPoll =>
            F.onCancel(
              outerPoll {
                F.uncancelable { innerPoll =>
                  F.onCancel(innerPoll(F.unit), innerFinalized.update(_ + 1))
                } *> F.uncancelable(_ => F.canceled)
              },
              outerFinalized.update(_ + 1)
            )
          }
        }
        outcome <- fiber.join
        outer <- outerFinalized.get
        inner <- innerFinalized.get
      } yield (outcome.isSuccess, outer, inner)

      assertEquals(
        pure.run(t),
        Outcome.Succeeded[Option, Int, (Boolean, Int, Int)](Some((true, 0, 0))))
    }

    test("observe nested self-cancel inside a polled region before continuing") {
      val t = for {
        ran <- F.ref(false)
        fiber <- F.start {
          F.uncancelable { poll =>
            poll {
              F.uncancelable(_ => F.canceled) *> ran.set(true)
            }
          }
        }
        _ <- fiber.join
        back <- ran.get
      } yield back

      assertEquals(pure.run(t), Outcome.Succeeded[Option, Int, Boolean](Some(false)))
    }

    test("preserve masked self-cancel through poll") {
      val maskedCancel = F.uncancelable(_ => F.canceled)
      val fa = F.onCancel(maskedCancel, F.never[Unit])

      assertEquals(pure.run(maskedCancel), Outcome.Canceled[Option, Int, Unit]())
      assertEquals(
        pure.run(F.uncancelable(poll => poll(maskedCancel))),
        Outcome.Canceled[Option, Int, Unit]())
      assertEquals(pure.run(fa), Outcome.Canceled[Option, Int, Unit]())
      assertEquals(
        pure.run(F.uncancelable(poll => poll(fa))),
        Outcome.Canceled[Option, Int, Unit]())
    }

    test("run a guarantee finalizer around a masked self-cancel") {
      val fa = F.guarantee(F.uncancelable(_ => F.canceled), F.never[Unit])

      assertEquals(pure.run(fa), Outcome.Succeeded[Option, Int, Unit](None))
    }

    test("associate finalizers across an uncancelable boundary") {
      val left = F.uncancelable(_ => F.onCancel(F.canceled, F.never[Unit]))
      val right = F.onCancel(F.uncancelable(_ => F.canceled), F.never[Unit])

      assertEquals(pure.run(left), Outcome.Canceled[Option, Int, Unit]())
      assertEquals(pure.run(right), Outcome.Canceled[Option, Int, Unit]())
    }

    test("implement locals via Kleisli and FreeT") {
      import cats.{~>, Eval, Id}
      import cats.data.Kleisli
      import cats.free.FreeT
      import cats.syntax.all._

      type F[A] = FreeT[Id, Kleisli[Eval, Int, *], A]

      def read[A](f: Int => F[A]): F[A] =
        FreeT.liftT(Kleisli.ask[Eval, Int]).flatMap(f)

      def withLocal[A](i: Int)(fa: F[A]): F[A] =
        fa.mapK(new (Kleisli[Eval, Int, *] ~> Kleisli[Eval, Int, *]) {
          def apply[a](kea: Kleisli[Eval, Int, a]) =
            Kleisli((_: Int) => kea(i))
        })

      def run[A](i: Int)(fa: F[A]): A =
        fa.runM(fta => Kleisli.liftF(Eval.now(fta))).apply(i).value

      val _ = run(1) {
        withLocal(42) {
          read { i =>
            FreeT
              .liftT[Id, Kleisli[Eval, Int, *], Unit](
                Kleisli.liftF[Eval, Int, Unit](Eval.later(assertEquals(i, 42))))
              .flatMap(_ =>
                read { i2 => FreeT.liftT(Kleisli.liftF(Eval.later(assertEquals(i2, 42)))) })
          }
        } *> read { i => FreeT.liftT(Kleisli.liftF(Eval.later(assertEquals(i, 1)))) }
      }
    }

    test("race TimeT values against never") {
      type T[A] = TimeT[F, A]
      val T = GenTemporal[T, Int]

      assertEquals(
        pure.run(TimeT.run(T.race(T.pure(1), T.never[Unit]))),
        Outcome.Succeeded[Option, Int, Either[Int, Unit]](Some(Left(1))))
      assertEquals(
        pure.run(TimeT.run(T.race(T.never[Unit], T.pure(1)))),
        Outcome.Succeeded[Option, Int, Either[Unit, Int]](Some(Right(1))))
      assertEquals(
        pure.run(TimeT.run(T.race(T.sleep(1.second).as(1), T.never[Unit]))),
        Outcome.Succeeded[Option, Int, Either[Int, Unit]](Some(Left(1))))
      assertEquals(
        pure.run(TimeT.run(T.race(T.never[Unit], T.sleep(1.second).as(1)))),
        Outcome.Succeeded[Option, Int, Either[Unit, Int]](Some(Right(1))))
      assertEquals(
        pure.run(
          TimeT.run(T.race(T.sleep(2.seconds).as("slow"), T.sleep(1.second).as("fast")))),
        Outcome.Succeeded[Option, Int, Either[String, String]](Some(Right("fast")))
      )
      assertEquals(
        pure.run(
          TimeT.run(T.race(T.sleep(1.second).as("fast"), T.sleep(2.seconds).as("slow")))),
        Outcome.Succeeded[Option, Int, Either[String, String]](Some(Left("fast")))
      )
      assertEquals(
        pure.run(TimeT.run(T.race(T.canceled, T.never[Unit]).void)),
        Outcome.Canceled[Option, Int, Unit]())
      assertEquals(
        pure.run(TimeT.run(T.race(T.never[Unit], T.canceled).void)),
        Outcome.Canceled[Option, Int, Unit]())
      assertEquals(
        pure.run(
          TimeT.run(T.race(TimeT.liftF(F.uncancelable(_ => F.canceled.as(1))), T.never[Unit]))),
        Outcome.Succeeded[Option, Int, Either[Int, Unit]](Some(Left(1)))
      )
      assertEquals(
        pure.run(
          TimeT.run(T.race(T.never[Unit], TimeT.liftF(F.uncancelable(_ => F.canceled.as(1)))))),
        Outcome.Succeeded[Option, Int, Either[Unit, Int]](Some(Right(1)))
      )
      assertEquals(
        pure.run(
          TimeT.run(T.race(TimeT.liftF(F.start(F.unit).flatMap(_.join).as(1)), T.never[Unit]))),
        Outcome.Succeeded[Option, Int, Either[Int, Unit]](Some(Left(1)))
      )
      assertEquals(
        pure.run(
          TimeT.run(T.race(T.never[Unit], TimeT.liftF(F.start(F.unit).flatMap(_.join).as(1))))),
        Outcome.Succeeded[Option, Int, Either[Unit, Int]](Some(Right(1)))
      )
    }

  }

  checkAll(
    "TimeT[PureConc]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  )
}
