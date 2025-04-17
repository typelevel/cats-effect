---
id: migration-guide
title: Migration Guide
---

## Summary

Here is an overview of the steps you should take to migrate your application to Cats Effect 3:

1. [Make sure your dependencies have upgraded](#make-sure-your-dependencies-have-upgraded)<!-- don't remove this comment - this ensures the vscode extension doesn't make this a ToC -->
1. [Run the Scalafix migration](#run-the-scalafix-migration) (optional)
1. [Upgrade dependencies and Cats Effect itself](#upgrade-dependencies)
1. [Fix remaining compilation issues](#fix-remaining-compilation-issues)
1. [Test your application](#test-your-application).

### Before You Begin: This Isn't A "Quick Start" Guide

This guide is meant for existing users of Cats Effect 2 who want to upgrade their applications
to 3.4.11.

> If you haven't used Cats Effect before and want to give it a try,
> please follow the [getting started guide](./getting-started.md) instead!

### 🤔 Need Help?

If any point of the migration turns out to be difficult and you feel like you need help, feel free to [explain your problem in Discord](https://discord.gg/QNnHKHq5Ts) and we will do our best to assist you.
If you spot a mistake in the guide or the library itself, you can [report an issue on GitHub](https://github.com/typelevel/cats-effect/issues/new)
or [fix it with a pull request](https://github.com/typelevel/cats-effect/compare).

### Context: What's Changed, What's the Same?

Cats Effect 3 (CE3 for short) is a complete redesign of the library.
Some abstractions known from Cats Effect 2 (CE2) have been removed, others changed responsibilities, and finally, new abstractions were introduced.

The `cats.effect.IO` type known from CE2 is still there, albeit with a different place in the type class hierarchy - namely, it doesn't appear in it.

The new set of type classes has been designed in a way that deliberately avoids coupling with `IO`, which makes the library more modular,
and allows library authors (as well as users of other effect types) to omit that dependency from their builds.

## Make Sure Your Dependencies Have Upgraded

Before you make any changes to your build or your code, you should make sure all of your direct and transitive dependencies have made releases compatible with Cats Effect 3.

There isn't any automated way to do this, but you can just go ahead and [try to upgrade the dependencies](#upgrade-dependencies), then stash the changes and return to here.

If you're using an open source library that hasn't made a compatible release yet, [let us know - we are keeping track of the efforts of library authors](https://github.com/typelevel/cats-effect/issues/1330) to publish compatible releases as soon as possible.

## Run the Scalafix Migration

Many parts of this migration can be automated by using the [Scalafix][scalafix] migration.

> Note: In case of projects using Scala Steward, the migration should automatically be applied when you receive the update.

If you want to trigger the migration manually, you can follow [the instructions here](https://github.com/typelevel/cats-effect/blob/series/3.x/scalafix/README.md). Remember to run it *before* making any changes to your dependencies' versions.

Now is the time to update `cats-effect` and **every dependency using it** to a CE3-compatible version.

## Upgrade Dependencies

At this point, if you've run the Scalafix migration, your code will not compile. However, you should hold off going through the list of errors and fixing the remaining issues yourself at this point.

If you're an [sbt][sbt] user, it is recommended that you upgrade to at least `1.5.0` before you proceed:

In your `project/build.properties`:

```diff
- sbt.version = 1.4.9
+ sbt.version = 1.5.0
```

This will enable eviction errors, which means your build will only succeed if all your dependencies
use compatible versions of each library (in the case of `cats-effect`, this will require your dependencies
all use either the 2.x.x versions or the 3.x.x versions).

Having upgraded sbt, you can try to upgrade Cats Effect:

### Which Modules Should I Use?

Cats Effect 3 splits the code dependency into multiple modules. If you were previously using `cats-effect`, you can keep doing so, but if you're a user of another effect system (Monix, ZIO, ...), or a library author, you might be able to depend on a subset of it instead.

The current non-test modules are:

```scala
"org.typelevel" %% "cats-effect-kernel" % "3.5.7",
"org.typelevel" %% "cats-effect-std"    % "3.5.7",
"org.typelevel" %% "cats-effect"        % "3.5.7",
```

- `kernel` - type class definitions, simple concurrency primitives
- `std` - high-level abstractions like `Console`, `Semaphore`, `Hotswap`, `Dispatcher`
- `core` - `IO`, `SyncIO`

> Note: It is recommended to upgrade to 2.4.0 first, to minimize the changes.

```diff
libraryDependencies ++= Seq(
  //...
-  "org.typelevel" %% "cats-effect" % "2.4.0",
+  "org.typelevel" %% "cats-effect" % "3.5.7",
  //...
)
```

Then run `update` or `evicted` in sbt. You should see something like the following:

```scala
sbt:demo> update
[error] stack trace is suppressed; run last core / update for the full output
[error] (core / update) found version conflict(s) in library dependencies; some are suspected to be binary incompatible:
[error]
[error] 	* org.typelevel:cats-effect_2.13:3.4.11 (early-semver) is selected over {2.3.1, 2.1.4}
[error] 	    +- com.example:core-core_2.13:0.0.7-26-3183519d       (depends on 3.4.11)
[error] 	    +- io.monix:monix-catnap_2.13:3.3.0                   (depends on 2.1.4)
[error] 	    +- com.github.valskalla:odin-core_2.13:0.11.0         (depends on 2.3.1)
[error]
[error]
[error] this can be overridden using libraryDependencySchemes or evictionErrorLevel
[error] Total time: 0 s, completed 27 Mar 2021, 17:51:52
```

This tells you that you need to upgrade both `monix-catnap` and `odin-core` before proceeding. Make sure `update` of all your project's modules passes before proceeding to the next point.

> Note: that some of the libraries listed might be transitive dependencies, which means
> you're depending on other projects that depend on them.
> Upgrading your direct dependencies should solve the transitive dependencies' incompatibilities as well.

## Fix Remaining Compilation Issues

#### New Type Class Hierarchy

Here's the new type class hierarchy. It might be helpful in understanding some of the changes:

<a href="https://raw.githubusercontent.com/typelevel/cats-effect/series/3.x/images/hierarchy.svg" target="_blank">
  <img src="https://raw.githubusercontent.com/typelevel/cats-effect/series/3.x/images/hierarchy.svg" alt="hierarchy" style="margin: 30px 0"/>
</a>

Most of the following are handled by [the Scalafix migration](#run-the-scalafix-migration). If you can, try that first!

> Note: package name changes were skipped from this guide. Most type classes are now in the `cats.effect.kernel` package,
> but you can access them through `cats.effect.<the typeclass>` too thanks to type aliases.

Assume this import for the rest of the guide:

```scala mdoc
import cats.effect.unsafe.implicits.global
```

Learn more about `global` [in the `IO` section](#io).

### Async

| Cats Effect 2.x                   | Cats Effect 3                                      |
| --------------------------------- | -------------------------------------------------- |
| `Async[F].async`                  | `Async[F].async_`                                  |
| `Async[F].asyncF(f)`              | `Async[F].async(f(_).as(none))`                    |
| `Async.shift`                     | nothing / `Spawn[F].cede` - See [below](#shifting) |
| `Async.fromFuture`                | `Async[F].fromFuture`                              |
| `Async.memoize`                   | `Concurrent[F].memoize`                            |
| `Async.parTraverseN`              | `Concurrent[F].parTraverseN`                       |
| `Async.parSequenceN`              | `Concurrent[F].parSequenceN`                       |
| `Async.parReplicateAN`            | `Concurrent[F].parReplicateAN`                     |
| `Async[F].liftIO`, `Async.liftIO` | `LiftIO[F].liftIO`                                 |
| `Async <: LiftIO`                 | No subtyping relationship                          |

#### `async` Signature

In Cats Effect 2, `Async` was able to lift asynchronous effects into `F`. `Concurrent` extended that ability
to allow canceling them - in CE3,
these two capabilities have been merged into `Async`, so all `Async` types
have to be cancelable to pass the laws.

A major outcome of this decision is that the `async` method supports cancelation. Here's the new signature:

```scala
trait Async[F[_]] {
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
}
```

We can divide the parameter `k` into the following:

- `Either[Throwable, A] => Unit` - the callback that will complete or fail this effect when called. This is identical as in CE2.
- `=> F[...]` (outer effect) - the effect of registering the callback. This would be e.g. `delay { window.setTimeout(() => cb(...)) }`.
- `Option[F[Unit]]` - an optional effect that will run if the action is canceled. Passing `None` here makes the whole `async(...)` uncancelable.

The most similar method to this in CE2 would be `Concurrent.cancelableF`:

```scala
// CE2!
def cancelableF[F[_], A](k: (Either[Throwable, A] => Unit) => F[F[Unit]])
```

The only difference being that there was always an effect for cancelation - now it's optional (the `F` inside `F`).

You can learn more about `Async` [on its page](./typeclasses/async.md).

#### Relationship with `LiftIO`

`LiftIO` is no longer part of the "kernel" type classes (being unlawful and strictly coupled to `IO`, which isn't part of `kernel` either), and has been moved to the `core` module (the `cats-effect` dependency).

`Async` is in `kernel` (`cats-effect-kernel`), so it can't depend on `LiftIO`, and thus doesn't extend it.
If you need `LiftIO` functionality, use it directly (or use the methods on `IO` like `to[F[_]: LiftIO]`).

#### Implementing Async

Types that used to implement `Async` but not `Concurrent` from CE2 might not be able to implement `Async` in CE3 -
this has an impact on users who have used polymorphic effects with an `F[_]: Async` constraint in their applications, where `F` was one of:

- [doobie](https://tpolecat.github.io/doobie/)'s `ConnectionIO`,
- [slick](http://scala-slick.org/)'s `DBIO` with [slick-effect](https://github.com/kubukoz/slick-effect),
- [ciris](https://cir.is)'s `ConfigValue`, or
- possibly others that we don't know of

Please refer to each library's appropriate documentation/changelog to see how to adjust your code to this change.

### `Blocker`

| Cats Effect 2.x                               | Cats Effect 3                               | Notes                                                                           |
| --------------------------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------- |
| `Blocker.apply`                               | -                                           | blocking is [provided by runtime](#how-does-blocking-work) |
| `Blocker#delay`                               | `Sync[F].blocking`, `Sync[F].interruptible`, `Sync[F].interruptibleMany` | `Blocker` was removed                                                           |
| `Blocker(ec).blockOn(fa)`, `Blocker.blockOnK` | [see notes](#no-blockon)                    |                                                                                 |

`Blocker` has been removed. Instead of that, you should either use your specific effect type's method of blocking...

```scala mdoc
import cats.effect.IO
import cats.effect.Sync

val program = IO.blocking(println("hello blocking!"))
```

or the `Sync` typeclass:

```scala mdoc
val programSync = Sync[IO].blocking(println("hello Sync blocking!"))
```

#### Interruptible Blocking

It is now possible to make the blocking task interruptible using [`Sync`](./typeclasses/sync.md):

```scala mdoc
val programInterruptible =
  Sync[IO].interruptible(println("hello interruptible blocking!"))
```

If we require our operation to be more sensitive to cancelation we can use `interruptibleMany`.
The difference between `interruptible` and `interruptibleMany` is that in case of cancelation
`interruptibleMany` will repeatedly attempt to interrupt until the blocking operation completes or exits,
on the other hand using `interruptible` the interrupt will be attempted only once.

#### How Does Blocking Work?

Support for blocking actions is provided by `IORuntime`. The runtime provides the blocking threads as needed.
(For other effect systems it could be a `Runtime` or `Scheduler`, etc.)
You can learn more about CE3 [schedulers](./schedulers.md) and [the thread model in comparison to CE2's](./thread-model.md).

```scala mdoc
val runtime = cats.effect.unsafe.IORuntime.global

def showThread() = java.lang.Thread.currentThread().getName()

IO(showThread())
  .product(IO.blocking(showThread()))
  .unsafeRunSync()(runtime)
```

#### No `blockOn`?

It should be noted that `blockOn` is missing. There is now no _standard_ way to wrap an effect and move it to a blocking pool,
but instead it's recommended that you wrap every blocking action in `blocking` separately.

If you _absolutely_ need to run a whole effect on a blocking pool, you can pass a blocking `ExecutionContext` to `Async[F].evalOn`.

> **Important note**: An effect wrapped with `evalOn` can still schedule asynchronous actions on any other threads.
> The only actions impacted by `evalOn` will be the ones that would otherwise run on the compute pool
> (because `evalOn`, in fact, changes the compute pool for a duration of the given effect).
>
> This is actually safer than CE2's `blockOn`, because after any `async` actions inside the effect,
> the rest of the effect will be shifted to the selected pool. Learn more about [shifting in CE3](#shifting).

```scala mdoc
import scala.concurrent.ExecutionContext

def myBlockingPool: ExecutionContext = ???

def myBlocking[A](fa: IO[A]) = fa.evalOn(myBlockingPool)
```


### `Bracket`

| Cats Effect 2.x               | Cats Effect 3                          | Notes                                    |
| ----------------------------- | -------------------------------------- | ---------------------------------------- |
| `Bracket[F].bracket`          | `MonadCancel[F].bracket`               |                                          |
| `Bracket[F].bracketCase`      | `MonadCancel[F].bracketCase`           | [`ExitCase` is now `Outcome`](#exitcase) |
| `Bracket[F].uncancelable(fa)` | `MonadCancel[F].uncancelable(_ => fa)` |                                          |
| `Bracket[F].guarantee`        | `MonadCancel[F].guarantee`             |                                          |
| `Bracket[F].guaranteeCase`    | `MonadCancel[F].guaranteeCase`         | [`ExitCase` is now `Outcome`](#exitcase) |
| `Bracket[F].onCancel`         | `MonadCancel[F].onCancel`              |                                          |

`Bracket` has mostly been renamed to `MonadCancel`, and the migration should be straightforward.
The `bracketCase` method is no longer a primitive, and is derived from
the primitives `uncancelable` and `onCancel`.

If you were using `uncancelable` using the extension method syntax, you can continue to do so without any changes.
In the case of usage through `Bracket[F, E]`, you can use the new method but ignoring the parameter provided in the lambda (see table above).

To learn what the new signature of `uncancelable` means, how you can use it in your programs after the migration, and other things about `MonadCancel`,
see [its docs](./typeclasses/monadcancel.md).

Another important change is replacing `ExitCase` with `Outcome`. Learn more [below](#exitcase).

### `Clock`

| Cats Effect 2.x                           | Cats Effect 3                           |
| ----------------------------------------- | --------------------------------------- |
| `Clock[F].realTime: TimeUnit => F[Long]`  | `Clock[F].realTime: F[FiniteDuration]`  |
| `Clock[F].monotonic: TimeUnit => F[Long]` | `Clock[F].monotonic: F[FiniteDuration]` |
| `Clock.instantNow`                        | `Clock[F].realTimeInstant`              |
| `Clock.create`, `Clock[F].mapK`           | -                                       |

`Clock` has been included in the new type class hierarchy and thus it also has laws (e.g. getting the monotonic time twice is guaranteed to return increasing values).

As for the user-facing differences, the most important ones are the signature changes of `realTime` and `monotonic`:
instead of having the user provide the time unit and getting a `Long`, the methods now return `FiniteDuration`:

```scala mdoc
import cats.effect.Clock

val clocks = for {
  before <- Clock[IO].monotonic
  after <- Clock[IO].monotonic
} yield (after - before)

clocks.unsafeRunSync()
```

Because `Clock` is no longer a "usual" value but a type class, it's not possible to create new values of it through `create` and `mapK`.

`Clock` is a part of `Sync` and thus it should be easy to get an instance inductively for any monad transformer, or directly for any effect that is a `Sync`.

### `Concurrent`

| Cats Effect 2.x                             | Cats Effect 3                   | Notes                                          |
| ------------------------------------------- | ------------------------------- | ---------------------------------------------- |
| `Concurrent[F].start`                       | `Spawn[F].start`                |                                                |
| `Concurrent[F].background`                  | `Spawn[F].background`           | Value in resource is now an `Outcome`          |
| `Concurrent[F].liftIO`, `Concurrent.liftIO` | `LiftIO[F].liftIO`              | `LiftIO` is in the `cats-effect` module        |
| `Concurrent <: LiftIO`                      | No subtyping relationship       | `LiftIO` is in the `cats-effect` module        |
| `Concurrent[F].race`                        | `Spawn[F].race`                 |                                                |
| `Concurrent[F].racePair`                    | `Spawn[F].racePair`             |                                                |
| `Concurrent[F].cancelable`                  | `Async.async`                   | Wrap side effects in F, cancel token in `Some` |
| `Concurrent[F].cancelableF`                 | `Async.async(f(_).map(_.some))` | `Some` means there is a finalizer to execute.  |
| `Concurrent[F].continual`                   | -                               | see [below](#continual)                        |
| `Concurrent.continual`                      | -                               | see [below](#continual)                        |
| `Concurrent.timeout`                        | `Temporal[F].timeout`           |                                                |
| `Concurrent.timeoutTo`                      | `Temporal[F].timeoutTo`         |                                                |
| `Concurrent.memoize`                        | `Concurrent[F].memoize`         |                                                |
| `Concurrent.parTraverseN`                   | `Concurrent[F].parTraverseN`    |                                                |
| `Concurrent.parSequenceN`                   | `Concurrent[F].parSequenceN`    |                                                |
| `Concurrent.parReplicateAN`                 | `Concurrent[F].parReplicateAN`  |                                                |

This is arguably the most changed type class. Similarly to `Async`, it is [no longer related to `LiftIO`](#relationship-with-liftio).

The primitive operations that used to be `Concurrent` have all been moved to other type classes:

- `Spawn` - a new type class, responsible for creating new fibers and racing them ([see more in `Spawn` docs](./typeclasses/spawn.md))
- `Async` - `cancelable` has been merged with `async`, so it ended up there. This was discussed in [the `Async` part of the guide](#async).
- `Temporal` - another new type class which extends `Concurrent` (so it's actually more powerful) with the ability to sleep. This is [the replacement of `Timer`](#timer).

The remaining part of `Concurrent` are the ability to create `Ref`s and `Deferred`s, which (together with the `Spawn` methods) enable the implementation of `memoize`.

If you're only using methods from `Spawn` or `Temporal`, you might be able to reduce your type class constraints to these.

#### Place in the Hierarchy

If you recall [the type class hierarchy](#new-type-class-hierarchy), `Concurrent` is no longer a child of `Async` and `Sync` - it's one of their parents.

This means `Concurrent` can no longer perform any kind of FFI on its own, and if you don't have `Sync` or `Async` in scope you can't even `delay`.
This allows writing code that deals with concurrency without enabling suspension of arbitrary side effects in the given region of your code.

For the migration purposes, this means you might have to replace some `F[_]: Concurrent` constraints with `F[_]: Async`, if you rely on `async`/`delay`.

#### `continual`

`Concurrent#continual` has been removed, as it was deemed the wrong tool for most use-cases.

If you desperately need it, here's a polyfill implementation you can use for the migration:

```scala mdoc
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._

def continual[F[_]: MonadCancelThrow, A, B](fa: F[A])(
  f: Either[Throwable, A] => F[B]
): F[B] = MonadCancel[F].uncancelable { poll =>
  poll(fa).attempt.flatMap(f)
}
```

> Note: It's recommended to use `uncancelable { poll => ... }`, `bracketCase` and `onCancel` directly instead.
> Learn more in [`MonadCancel` docs](./typeclasses/monadcancel.md).

#### `GenConcurrent`

The actual abstraction isn't `Concurrent` anymore, but rather a generalization of it for arbitrary error types, `GenConcurrent[F, E]`.
There are aliases set up in `cats.effect` and `cats.effect.kernel` to make it easier to use with `Throwable`, e.g.

```scala
type Concurrent[F[_]] = GenConcurrent[F, Throwable]
```

The same pattern is followed by `GenTemporal`/`Temporal` and `GenSpawn`/`Spawn`. While not strictly a migration-helping change, this allows you
to abstract away `Throwable` in parts of your code that don't need to know what the exact error type is.


### `Effect`, `ConcurrentEffect`, `SyncEffect`

| Cats Effect 2.x       | Cats Effect 3 |
| --------------------- | ------------- |
| `ConcurrentEffect[F]` | `Dispatcher`  |
| `Effect[F]`           | `Dispatcher`  |
| `Effect.toIOK`        | `Dispatcher`  |
| `SyncEffect[F]`       | `Dispatcher`  |

All the `*Effect` type classes have been removed. Instead, the recommended way to run a polymorphic effect is through a new interface, `Dispatcher`.

#### `Dispatcher`

`Dispatcher` allows running an effect in a variety of ways, including running to a `Future` or running synchronously (only on the JVM).

This is something you might want to do in a situation where you have an effect, but a third-party library expects a callback that has to complete synchronously (or in a `scala.concurrent.Future`, or a Java future).

You can get an instance of it with `Dispatcher.parallel[F]` (or `sequential[F]`) for any `F[_]: Async`:

```scala
object Dispatcher {
  def parallel[F[_]](await: Boolean = false)(implicit F: Async[F]): Resource[F, Dispatcher[F]]
  def sequential[F[_]](await: Boolean = false)(implicit F: Async[F]): Resource[F, Dispatcher[F]]
}
```

> Note: keep in mind the shape of that method: the resource is related to the lifecycle of all tasks you run with a dispatcher.
> When this resource is closed, **all its running tasks are canceled or joined** (depending on the `await` parameter).

Creating a `Dispatcher` is relatively lightweight, so you can create one even for each task you execute, but sometimes it's worth keeping a `Dispatcher` alive for longer.
To find out more, see [its docs](./std/dispatcher.md).

For example, given an imaginary library's interface like this:

```scala mdoc
trait Consumer[A] {
  def onNext(a: A): Unit
}
```

In which the `onNext` method would be called by something beyond our control, and we would only be supplying a `Consumer` to it,
In CE2 you could run an effect in `onNext` using `Effect` like this:

```scala
// CE2
def consumer[F[_]: Effect, A](handler: A => F[Unit]): Consumer[A] =
  new Consumer[A] {
    def onNext(a: A): Unit = handler(a).toIO.unsafeRunAndForget()
  }
```

In CE3, you would use `Dispatcher`:

```scala mdoc
import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher

// CE3
def consumer[F[_]: Async, A](handler: A => F[Unit]): Resource[F, Consumer[A]] =
  Dispatcher.sequential[F] map { dispatcher =>
    new Consumer[A] {
      def onNext(a: A): Unit = dispatcher.unsafeRunAndForget(handler(a))
    }
  }
```

The `Resource` produced by this method will manage the lifetime of the `Dispatcher` instance - while it's open,
the dispatcher instance will be able to run actions that we want to execute in the `onNext` callback.

An alternative approach to creating a `Dispatcher` on our own would be taking one as a parameter.
It is recommended to pass it **explicitly**, as `Dispatcher` isn't a type class and there can be many instances of it in an application.

```scala mdoc
def consumer2[F[_], A](dispatcher: Dispatcher[F], handler: A => F[Unit]): Consumer[A] =
  new Consumer[A] {
    def onNext(a: A): Unit = dispatcher.unsafeRunAndForget(handler(a))
  }
```

### `ContextShift`

| Cats Effect 2.x          | Cats Effect 3             |
| ------------------------ | ------------------------- |
| `ContextShift[F].shift`  | nothing / `Spawn[F].cede` |
| `ContextShift[F].evalOn` | `Async[F].evalOn`         |


#### Shifting

The `IO.shift` / `ContextShift[F].shift` methods are gone, and they don't have a fully compatible counterpart.

In CE2, `shift` would ensure the next actions in the fiber would be scheduled on the `ExecutionContext` instance (or the `ContextShift` instance) provided in the parameter.
This was used for two reasons:

- to switch back from a thread pool not managed by the effect system (e.g. a callback handler in a Java HTTP client)
- to reschedule the fiber on the given `ExecutionContext`, which would give other fibers a chance to run on the underlying pool's threads.
  This is called yielding to the scheduler.

There is no longer a need for shifting back (1), because interop with callback-based libraries is done through methods in `Async`, which now **switch back to the appropriate thread pool automatically**.

Yielding back to the scheduler (2) can now be done with `Spawn[F].cede`.

### `Deferred`

In CE2, completing a `Deferred` after it's already been completed would result in a failed effect. This is not the case in CE3.

Before:

```scala
// CE2
trait Deferred[F[_], A] {
  def complete(a: A): F[Unit]
}
```

After:

```scala
// CE3
trait Deferred[F[_], A] {
  def complete(a: A): F[Boolean]
}
```

Because creating a `Deferred` is no longer restricted to effects using `Throwable` as their error type (you can use any `GenConcurrent[F, _]`),
there is no way to fail the effect from inside the library for an arbitrary error type - so instead of an `F[Unit]` that could fail,
the method's type is now `F[Boolean]`, which will complete with `false` if there were previous completions.

**This is equivalent to `tryComplete` in CE2**. Make sure your code doesn't rely on calling `complete` more than once to fail.

### `ExitCase`, `Fiber`

| Cats Effect 2.x          | Cats Effect 3                  |
| ------------------------ | ------------------------------ |
| `ExitCase[E]`            | `Outcome[F, E, A]`             |
| `Fiber[F, A]`            | `Fiber[F, E, A]`               |
| `Fiber[F, A].join: F[A]` | `Fiber[F, E, A].joinWithNever` |

#### `ExitCase`

In CE2, the final status of an effect's execution was represented as `ExitCase`:

```scala
// CE2
sealed trait ExitCase[+E]
case object Completed extends ExitCase[Nothing]
final case class Error[+E](e: E) extends ExitCase[E]
case object Canceled extends ExitCase[Nothing]
```

The closest type corresponding to it in CE3 is `Outcome`:

```scala
// CE3
sealed trait Outcome[F[_], E, A]
final case class Succeeded[F[_], E, A](fa: F[A]) extends Outcome[F, E, A]
final case class Errored[F[_], E, A](e: E) extends Outcome[F, E, A]
final case class Canceled[F[_], E, A]() extends Outcome[F, E, A]
```

If we ignore the various differences in type parameter variance (which were mostly added for better Scala 3 support),
the elephant in the room is `Succeeded` (the new `Completed`) - it has an `F[A]` field, which will contain the value the effect produced in the successful case. This means methods like `bracketCase` / `Resource.allocateCase` can use this result when cleaning up the resource.

If you're simply migrating code that was using these methods, just renaming to the appropriate new names (and possibly fixing some pattern matches)
should get you to a compiling state. For more information about Outcome, see [`Spawn` docs](./typeclasses/spawn.md).

#### `Fiber`

Mostly unchanged, `Fiber` still has a `cancel` method, and a `join` method:

```scala
trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[Outcome[F, E, A]]
}
```

However, there are still some differences here: first of all, `join` doesn't just return `F[A]`, but the whole `Outcome` of it.
This is also why `Fiber` got the extra type parameter `E`.

In CE2, the `F[A]` type of `join` meant that in case the fiber was canceled, `join` would never complete.
That behavior is still available as `joinWithNever` (you can learn more about it [in `Spawn` docs](./typeclasses/spawn.md#joining)),
but it's often safer to move away from it and pass an explicit cancelation handler (for example, a failing one) using `fiber.joinWith(onCancel: F[A])`.

### `IO`

| Cats Effect 2.x                   | Cats Effect 3                                  | Notes                                                  |
| --------------------------------- | ---------------------------------------------- | ------------------------------------------------------ |
| `IO#as`                           | `IO#as(a)` / `IO#map(_ => a)`                  | the argument isn't by-name anymore                     |
| `IO#runAsync`, `IO#runCancelable` | Unsafe variants or [`Dispatcher`](#dispatcher) | Methods that run an IO require an implicit `IORuntime` |
| `IO#unsafe*`                      | The same or similar                            | Methods that run an IO require an implicit `IORuntime` |
| `IO#unsafeRunTimed`               | -                                              |                                                        |
| `IO#background`                   | The same                                       | Value in resource is now an `Outcome`                  |
| `IO#guaranteeCase`/`bracketCase`  | The same                                       | [`ExitCase` is now `Outcome`](#exitcase)               |
| `IO#parProduct`                   | `IO#both`                                      |                                                        |
| `IO.suspend`                      | `IO.defer`                                     |                                                        |
| `IO.shift`                        | See [shifting](#shifting)                      |                                                        |
| `IO.cancelBoundary`               | `IO.cede`                                      | Also [performs a yield](#shifting)                     |

Most changes in `IO` are straightforward, with the exception of the "unsafe" methods that cause an IO to be run.

Aside from the renamings of these methods, they all now take an implicit `IORuntime`.

```scala
import cats.effect.IO

def io: IO[Unit] = ???

io.unsafeRunSync()
// error: Could not find an implicit IORuntime.
//
// Instead of calling unsafe methods directly, consider using cats.effect.IOApp, which
// runs your IO. If integrating with non-functional code or experimenting in a REPL / Worksheet,
// add the following import:
//
// import cats.effect.unsafe.implicits.global
//
// Alternatively, you can create an explicit IORuntime value and put it in implicit scope.
// This may be useful if you have a pre-existing fixed thread pool and/or scheduler which you
// wish to use to execute IO programs. Please be sure to review thread pool best practices to
// avoid unintentionally degrading your application performance.
//
// io.unsafeRunSync()
// ^^^^^^^^^^^^^^^^^^
```

Follow the advice from the "missing implicit" error message whenever you need this functionality.
Note that some of your direct `unsafeRun*` calls might be possible to replace with [`Dispatcher`](#dispatcher).

### `IOApp`

| Cats Effect 2.x    | Cats Effect 3     |
| ------------------ | ----------------- |
| `executionContext` | `runtime.compute` |
| `contextShift`     | -                 |
| `timer`            | -                 |

Instead of the `executionContext` used to build the `ContextShift` instance,
you now have access to the `IORuntime` value that'll be used to run your program.

Because `IO` only expects an `IORuntime` when you run it, there is always an instance of the `Temporal` and `Clock` type classes,
so you don't need to pass `Timer[IO]` and `ContextShift[IO]` or `Concurrent[IO]` anymore:
just use `IO.sleep` and other methods directly, no implicits involved.

There's also a simpler variant of `IOApp` if you don't need the command line arguments or don't want to deal with exit codes:

```scala mdoc
import cats.effect.IO
import cats.effect.IOApp

object Demo extends IOApp.Simple {
  def run: IO[Unit] = IO.println("hello Cats!")
}
```

This change has been added to the Cats Effect 2 series in [2.4.0](https://github.com/typelevel/cats-effect/releases/tag/v2.4.0).

### `MVar`

`MVar` has been removed with no direct replacement.

Depending on how you used it, you might be able to replace it with [`monix-catnap`'s implementation](https://monix.io/docs/current/catnap/mvar.html), a single-element [`Queue`](./std/queue.md) or a specialized utility built with [`Ref`](./std/ref.md) and [`Deferred`](./std/deferred.md).

### Sync

| Cats Effect 2.x   | Cats Effect 3   |
| ----------------- | --------------- |
| `Sync[F].suspend` | `Sync[F].defer` |

### `Resource`

| Cats Effect 2.x                      | Cats Effect 3                | Notes                                                    |
| ------------------------------------ | ---------------------------- | -------------------------------------------------------- |
| `Resource.parZip`                    | `Resource.both`              |                                                          |
| `Resource.liftF`                     | `Resource.eval`              |                                                          |
| `Resource.fromAutoCloseableBlocking` | `Resource.fromAutoCloseable` | The method always uses `blocking` for the cleanup action |

### Timer

| Cats Effect 2.x  | Cats Effect 3       |
| ---------------- | ------------------- |
| `Timer[F].clock` | `Clock[F]`          |
| `Timer[F].sleep` | `Temporal[F].sleep` |

For `Clock`, see [the relevant part of the guide](#clock).

Similarly to `Clock`, `Timer` has been replaced with a lawful type class, `Temporal`. Learn more in [its documentation](./typeclasses/temporal.md).

## Test Your Application

If you followed this guide, all your dependencies are using the 3.x releases of Cats Effect, your code compiles and your tests pass,
the process is probably done - at this point you should do the usual steps you make after major changes in your application:
running integration/end to end tests, manual testing, canary deployments and any other steps that might
typically be done in your environment.

Enjoy using Cats Effect 3!

## FAQ / Examples

### Why does `Outcome#Succeeded` contain a value of type `F[A]` rather than type `A`?

This is to support monad transformers. Consider

```scala
val oc: OutcomeIO[Int] =
  for {
    fiber <- Spawn[OptionT[IO, *]].start(OptionT.none[IO, Int])
    oc <- fiber.join
  } yield oc
```

If the fiber succeeds then there is no value of type `Int` to be wrapped in `Succeeded`,
hence `Succeeded` contains a value of type `OptionT[IO, Int]` instead.

In general you can assume that binding on the value of type `F[A]` contained in
`Succeeded` does not perform further effects. In the case of `IO` that means
that the outcome has been constructed as `Outcome.Succeeded(IO.pure(result))`.

[sbt]: https://scala-sbt.org
[scalafix]: https://scalacenter.github.io/scalafix/
