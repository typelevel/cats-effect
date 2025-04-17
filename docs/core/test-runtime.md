---
id: test-runtime
title: Test Runtime
---

While it is always ideal to compartmentalize the majority of your application's business logic within non-effectful functions (i.e. functions that do not involve `IO`) simply because they are far easier to unit test, it is also frequently necessary to write tests for complex logic inextricably bound to effectful functions. These functions are much more complex to test due to the fact that they can perform arbitrary effects (such as reading files or communicating over network sockets), encode non-determinism, run in parallel, and worst of all, interact with time.

Wall clock time is a difficult thing to work with in tests for two reasons. First, time is highly imprecise, by definition. A program which does nothing but repeatedly sample `IO.realTime` will get different values each time it is run. Even the length of the intervals will change significantly. `IO.monotonic` can and will reduce the error relative to sampling system time, but the fact remains that the JVM is not a real-time platform, consumer operating systems are not real-time OSes, and almost no one is running software on real-time hardware. This in turn means that tests which deal with the *measuring* of time in any direct or indirect way must often be built with some allowable margin for error, otherwise the tests would be far too unreliable, particularly when run on CI servers.

As if that weren't bad enough, there is a second reason that time is difficult to test: `IO.sleep` is (obviously) slow. If the function you are testing sleeps for one hour, then your test will take at least one hour to run. For this reason, most practical tests involving time are written with very short time intervals (e.g. `100.millis`). Frameworks such as [MUnit](https://scalameta.org/munit/) and [Specs2](http://etorreborre.github.io/specs2/) will run multiple test examples in parallel, ensuring that these asynchronous sleeps do not compound to result in extremely long test suite run times, but even short sleeps are time wasted. Worse-still, this compounds the first problem with time (inaccuracy of measurement), since it means that the error factors may be on the order of 2-3x the `sleep` itself!

All of this is highly undesirable. Fortunately, due to the fact that Cats Effect's `IO` (and accompanying `Temporal` typeclass) encodes a full suite of time primitives, most programs within the CE ecosystem are written in terms of those functions (`IO.realTime`, `IO.monotonic`, and `IO.sleep`) rather than directly accessing system primitives. For this reason, it is possible for Cats Effect to provide a better solution to this entire problem space: `TestControl`.

> Note: If you are testing an `IO`-related function which does *not* use temporal functionality, then you likely will not benefit from `TestControl` and you should instead rely on projects like [MUnit Cats Effect](https://github.com/typelevel/munit-cats-effect) or [Cats Effect Testing](https://github.com/typelevel/cats-effect-testing).

## Mocking Time

Simply put, `TestControl` is a mock runtime for Cats Effect `IO`. It fully implements the entirety of `IO`'s functionality, including fibers, asynchronous callbacks, and time support. Where `TestControl` differs from the default `IORuntime` is in three critical areas.

First, it only uses a single thread to execute all `IO` actions (similar to JavaScript). This means, among other things, that it may *mask* concurrency bugs and race conditions! This does not mean it is a *deterministic* runtime, however. Unlike a true production runtime, even a single threaded one, `TestControl` does not (usually) execute pending fibers in order using a structure like a queue. Instead, it randomly selects between all pending fibers at each yield point. This makes it considerably better at identifying subtle sequencing bugs and assumptions in code under test, since it is simulating a form of nondeterministic sequencing between fibers.

Second, `TestControl`'s internal notion of time only advances in response to external reconfiguration of the runtime. In other words, within a `TestControl` universe, `IO`'s notion of time is artificially controlled and limited to only what the external controller dictates it to be. All three time functions – `realTime`, `monotonic`, and `sleep` – behave consistently within this control system. In other words, if you `sleep` for `256.millis` and measure the current time before and after, you will find the latter measurement will be at least `256.millis` beyond the former. The only caveat here is that `TestControl` cannot simulate the natural passage of time which occurs due to CPU cycles spent on calculations. From the perspective of a `TestControl` universe, all computation, all side-effects (including things like disk or network), all memory access, all *everything* except for `sleep` is instantaneous and does not cause time to advance. This can have some unexpected consequences.

Third, and related to the second, `TestControl` in its most fundamental mode only operates in response to *external* user action. It exposes a set of functions which make it possible to advance program execution up to a certain point, then suspend *the entire program* and examine state, check to see if any results have been produced, look at (and potentially advance!) the current time, and so on. In a sense, you can think of this functionality like a very powerful debugger for any `IO`-based application (even those which use abstract effects and composable systems such as Fs2 or Http4s!), but embedded within a harness in which you can write assertions and seamlessly use together with any major test framework.

For those migrating code from Cats Effect 2, `TestControl` is a considerably more powerful and easier-to-use tool than previously-common techniques involving mock instances of the `Clock` typeclass.

In order to use `TestControl`, you will need to bring in the **cats-effect-testkit** dependency:

```scala
libraryDependencies += "org.typelevel" %% "cats-effect-testkit" % "3.5.7" % Test
```

## Example

For the remainder of this page, we will be writing tests which verify the behavior of the following function:

```scala mdoc
import cats.effect.IO
import cats.effect.std.Random
import scala.concurrent.duration._

def retry[A](ioa: IO[A], delay: FiniteDuration, max: Int, random: Random[IO]): IO[A] =
  if (max <= 1)
    ioa
  else
    ioa handleErrorWith { _ =>
      random.betweenLong(0L, delay.toNanos) flatMap { ns =>
        IO.sleep(ns.nanos) *> retry(ioa, delay * 2, max - 1, random)
      }
    }
```

As you likely inferred from reading the sources, `retry` implements a simple exponential backoff semantic. For a given `IO[A]`, it will re-run that action up to `max` times or until a successful result is produced. When an error is raised, it will `sleep` for a *random* time interval between 0 and the specified `delay` before retrying again. Each time `retry` loops, the `delay` is doubled. If an error is raised following the maximum number of retries, it is produced back to the caller.

This is *exactly* the sort of functionality for which `TestControl` was built to assist. It isn't straightforward to meaningfully refactor `retry` into pure and impure elements, and even if we were to do so, it would be difficult to get full confidence that the composed whole works as expected. Indeed, the whole thing is riddled with subtle corner cases that are easy to get slightly wrong one way or another. For example, in the original draft of the above snippet, the guarding conditional was `max <= 0`, which is an example of an off-by-one error that is difficult to catch without proper unit testing.

### Full Execution

The first sort of test we will attempt to write comes in the form of a *complete* execution. This is generally the most common scenario, in which most of the power of `TestControl` is unnecessary and the only purpose to the mock runtime is to achieve deterministic and fast time:

```scala
test("retry at least 3 times until success") {
  case object TestException extends RuntimeException

  var attempts = 0
  val action = IO {
    attempts += 1

    if (attempts != 3)
      throw TestException
    else
      "success!"
  }

  val program = Random.scalaUtilRandom[IO] flatMap { random =>
    retry(action, 1.minute, 5, random)
  }

  TestControl.executeEmbed(program).assertEquals("success!")
}
```

In this test (written using [MUnit Cats Effect](https://github.com/typelevel/munit-cats-effect)), the `action` program counts the number of `attempts` and only produces `"success!"` precisely on the third try. Every other time, it raises a `TestException`. This program is then transformed by `retry` with a `1.minute` delay and a maximum of 5 attempts.

Under the production `IO` runtime, this test could take up to 31 minutes to run! With `TestControl.executeEmbed`, it requires a few milliseconds at most. The `executeEmbed` function takes an `IO[A]`, along with an optional `IORuntimeConfig` and random seed (which will be used to govern the sequencing of "parallel" fibers during the run) and produces an `IO[A]` which runs the given `IO` fully to completion. If the `IO` under test throws an exception, is canceled, or *fails to terminate*, `executeEmbed` will raise an exception in the resulting `IO[A]` which will cause the entire test to fail.

> Note: Because `TestControl` is a mock, nested `IO` runtime, it is able to detect certain forms of non-termination within the programs under test! In particular, programs like `IO.never` and similar will be correctly detected as deadlocked and reported as such. However, programs which never terminate but do *not* deadlock, such as `IO.unit.foreverM`, cannot be detected and will simply never terminate when run via `TestControl`. Unfortunately, it cannot provide a general solution to the [Halting Problem](https://en.wikipedia.org/wiki/Halting_problem).

In this case, we're testing that the program eventually retries its way to success, and we're doing it without having to wait for real clock-time `sleep`s. For *most* scenarios involving mocked time, this kind of functionality is sufficient.

### Stepping Through the Program

For more advanced cases, `executeEmbed` may not be enough to properly measure the functionality under test. For example, if we want to write a test for `retry` which shows that it always `sleep`s for some time interval that is between zero and the exponentially-growing maximum `delay`. This is relatively difficult to do in terms of `executeEmbed` without adding some side-channel to `retry` itself which reports elapsed time with each loop.

Fortunately, `TestControl` provides a more general function, `execute`, which provides precisely the functionality needed to handle such cases:

```scala
test("backoff appropriately between attempts") {
  case object TestException extends RuntimeException

  val action = IO.raiseError(TestException)
  val program = Random.scalaUtilRandom[IO] flatMap { random =>
    retry(action, 1.minute, 5, random)
  }

  TestControl.execute(program) flatMap { control =>
    for {
      _ <- control.results.assertEquals(None)
      _ <- control.tick

      _ <- 0.until(4) traverse { i =>
        for {
          _ <- control.results.assertEquals(None)

          interval <- control.nextInterval
          _ <- IO(assert(interval >= 0.nanos))
          _ <- IO(assert(interval < (1 << i).minute))
          _ <- control.advanceAndTick(interval)
        } yield ()
      }

      _ <- control.results.assertEquals(Some(Outcome.failed(TestException)))
    } yield ()
  }
}
```

In this test, we're using `TestControl.execute`, which has a very similar signature to `executeEmbed`, save for the fact that it produces an `IO[TestControl[A]]` rather than a simple `IO[A]`. Using `flatMap`, we can get access to that `TestControl[A]` value and use it to directly manipulate the execution of the `program` under test. Going line-by-line:

```scala
_ <- control.results.assertEquals(None)
_ <- control.tick
```

`TestControl` allows us to ask for the results of the `program` at any time. If no results have been produced yet, then the result will be `None`. If the program has completed, produced an exception, or canceled, the corresponding `Outcome` will be returned within a `Some`. Once set, `results` will never change (because the `program` will have stopped executing!).

`TestControl` is a bit like a debugger with a breakpoint set on the very first line: at the start of the execution, absolutely nothing has happened, so we can't possibly have any `results`. The assertion in question verifies this fact. Immediately afterward, we execute `tick`. This action causes the `program` to run fully until all fibers are `sleep`ing, or until the `program` completes. In this case, there is only one fiber, and it will sleep immediately after attempting `action` for the very first time. This is where `tick` stops executing.

The exact semantic here is that `tick` by itself *will not advance time*. It will run the program for as long as it can without advancing time by even a single nanosecond. Once it reaches a point where all fibers are sleeping and awaiting time's march forward, it yields control back to the caller, allowing us to examine current state.

At this point, we could call `results` again, but the `program` most definitely has not completed executing. We could also check the `isDeadlocked` function to see if perhaps all of the fibers are currently hung, rather than `sleep`ing (it will be `false` in this case).

More usefully, we could ask what the `nextInterval` is. When all active fibers are asleep, `TestControl` must advance time by *at least* the minimum `sleep` across all active fibers in order to allow any of them to move forward. This minimum value is produced by `nextInterval`. In our case, this should be exactly the current backoff, between zero and our current `delay`.

Since we know we're going to retry five times and ultimately fail (since the `action` never succeeds), we take advantage of `traverse` to write a simple loop within our test. For each retry, we test the following:

```scala
for {
  _ <- control.results.assertEquals(None)

  interval <- control.nextInterval
  _ <- IO(assert(interval >= 0.nanos))
  _ <- IO(assert(interval < (1 << i).minute))
  _ <- control.advanceAndTick(interval)
} yield ()
```

In other words, we verify that the `results` are still `None`, which would catch any scenario in which `retry` aborts early and doesn't complete the full cycle of attempts. Then, we retrieve the `nextInterval`, which will be between zero and the current `delay`. Here we need to do a bit of math and write some bounding assertions, since the *exact* interval will be dependent on the `Random` we passed to `retry`. If we really wanted to make the test deterministic, we *could* use a mock `Random`, but in this case it just isn't necessary.

Once we've verified that the program is `sleep`ing for the requisite time period, we call `advanceAndTick` with *exactly* that time interval. This function is the composition of two other functions – `advance` and `tick` (which we saw previously) – and does exactly what it sounds: advances the clock and triggers the mock runtime to execute the program until the next point at which all fibers are `sleep`ing (which *should* be the next loop of `retry`).

Once we've attempted the action five times, we want to write one final assertion which checks that the error from `action` is propagated through to the results:

```scala
_ <- control.results.assertEquals(Some(Outcome.failed(TestException)))
```

We finally have `results`, since the `program` will have terminated with an exception at this point.

#### Deriving `executeEmbed`

As you might now expect, `executeEmbed` is actually implemented in terms of `execute`:

```scala
def executeEmbed[A](
    program: IO[A],
    config: IORuntimeConfig = IORuntimeConfig(),
    seed: Option[String] = None): IO[A] =
  execute(program, config = config, seed = seed) flatMap { c =>
    val nt = new (Id ~> IO) { def apply[E](e: E) = IO.pure(e) }

    val onCancel = IO.defer(IO.raiseError(new CancellationException()))
    val onNever = IO.raiseError(new NonTerminationException())
    val embedded = c.results.flatMap(_.map(_.mapK(nt).embed(onCancel)).getOrElse(onNever))

    c.tickAll *> embedded
  }
```

If you ignore the messy `map` and `mapK` lifting within `Outcome`, this is actually a relatively simple bit of functionality. The `tickAll` effect causes `TestControl` to `tick` until a `sleep` boundary, then `advance` by the necessary `nextInterval`, and then repeat the process until either `isDeadlocked` is `true` or `results` is `Some`. These results are then retrieved and embedded within the outer `IO`, with cancelation and non-termination being reflected as exceptions.

## Gotchas

It is very important to remember that `TestControl` is a *mock* runtime, and thus some programs may behave very differently under it than under a production runtime. Always *default* to testing using the production runtime unless you absolutely need an artificial time control mechanism for exactly this reason.

To give an intuition for the type of program which behaves strangely under `TestControl`, consider the following pathological example:

```scala
IO.cede.foreverM.start flatMap { fiber =>
  IO.sleep(1.second) *> fiber.cancel
}
```

In this program, we are creating a fiber which yields in an infinite loop, always giving control back to the runtime and never making any progress. Then, in the main fiber, we `sleep` for one second and `cancel` the other fiber. In both the JVM and JavaScript production runtimes, this program does exactly what you expect and terminates after (roughly) one second.

Under `TestControl`, this program will execute forever and never terminate. What's worse is it will also never reach a point where `isDeadlocked` is `true`, because it will never deadlock! This perhaps-unintuitive outcome happens because there is *at least one* fiber in the program which is active and not `sleep`ing, and so `tick` will continue giving control to *that* fiber without ever advancing the clock. It is possible to test this kind of program by using `tickOne` and `advance`, but you cannot rely on `tick` to return control when some fiber is remaining active.

Another common pitfall with `TestControl` is the fact that you need to be careful to *not* advance time *before* a `IO.sleep` happens! Or rather, you are perfectly free to do this, but it probably won't do what you think it will do. Consider the following:

```scala
TestControl.execute(IO.sleep(1.second) >> IO.realTime) flatMap { control =>
  for {
    _ <- control.advanceAndTick(1.second)
    _ <- control.results.assertEquals(Some(Outcome.succeeded(1.second)))
  } yield ()
}
```

The above is very intuitive! Unfortunately, it is also wrong. The problem becomes a little clearer if we desugar `advanceAndTick`:

```scala
TestControl.execute(IO.sleep(1.second) >> IO.realTime) flatMap { control =>
  for {
    _ <- control.advance(1.second)
    _ <- control.tick
    _ <- control.results.assertEquals(Some(Outcome.succeeded(1.second)))
  } yield ()
}
```

We're instructing `TestControl` to advance the clock *before* we `sleep`, and then we `tick`, which causes the fiber to reach the `IO.sleep` action, which then sleeps for an additional `1.second`, meaning that `results` will still be `None`! You can think of this a bit like setting a breakpoint on the first line of your application, starting a debugger, and then examining the variables expecting to see values from later in the application.

The solution is to add an additional `tick` to execute the "beginning" of the program (from the start up until the `sleep`(s)):

```scala
TestControl.execute(IO.sleep(1.second) >> IO.realTime) flatMap { control =>
  for {
    _ <- control.tick
    _ <- control.advance(1.second)
    _ <- control.tick
    _ <- control.results.assertEquals(Some(Outcome.succeeded(1.second)))
  } yield ()
}
```

Now everything behaves as desired: the first `tick` sequences the `IO.sleep` action, after which we advance the clock by `1.second`, and then we `tick` a second time, sequencing the `IO.realTime` action and returning the result of `1.second` (note that `TestControl` internal `realTime` and `monotonic` clocks are identical and always start at `0.nanos`).
