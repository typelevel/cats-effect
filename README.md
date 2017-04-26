# cats-effect 

[![Build Status](https://travis-ci.org/typelevel/cats-effect.svg?branch=master)](https://travis-ci.org/typelevel/cats-effect) [![Gitter](https://img.shields.io/gitter/room/typelevel/cats.svg)](https://gitter.im/typelevel/cats) [![Maven Central](https://img.shields.io/maven-central/v/org.typelevel/cats-effect_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.typelevel%22%20AND%20a%3Acats-effect*) [![Coverage Status](https://codecov.io/gh/typelevel/cats-effect/coverage.svg?branch=master)](https://codecov.io/gh/typelevel/cats-effect?branch=master)

> For when purity just isn't impure enough.

This project aims to provide a standard [`IO`](https://www.javadoc.io/page/org.typelevel/cats-effect_2.12/latest/cats/effect/IO.html) type for the [cats](http://typelevel.org/cats/) ecosystem, as well as a set of typeclasses (and associated laws!) which characterize general effect types.  This project was *explicitly* designed with the constraints of the JVM and of JavaScript in mind.  Critically, this means two things:

- Manages both synchronous *and* asynchronous (callback-driven) effects
- Compatible with a single-threaded runtime

In this way, `IO` is more similar to common `Task` implementations than it is to the classic `scalaz.effect.IO` or even Haskell's `IO`, both of which are purely synchronous in nature.  As Haskell's runtime uses green threading, a synchronous `IO` (and the requisite thread blocking) makes a lot of sense.  With Scala though, we're either on a runtime with native threads (the JVM) or only a single thread (JavaScript), meaning that asynchronous effects are every bit as important as synchronous ones.

This project does *not* attempt to provide any tools for concurrency or parallelism.  The only function which does any sort of thread or thread-pool manipulation of *any* sort is `shift` (on both `IO` and `Effect`), which takes a given computation and shifts it to a different thread pool.  This function mostly exists because it turns out to be somewhat difficult to do useful things with `IO` if you *don't* have this function.  At any rate, it is impossible to define *safe* and practical concurrent primitives solely in terms of `IO`.  If you want concurrency, you should use a streaming framework like fs2 or Monix.  Note that both of these frameworks are, at least conceptually, compatible with `IO`.

## Usage

At present, the only public releases of cats-effect are hash snapshots (see [below](#versioning-and-compatibility)).  The most current snapshot can be found in the maven badge at the top of this readme.  If you are a very brave sort, you are free to depend on these snapshots; they are stable versions, as they are derived from the git hash rather than an unstable `-SNAPSHOT` suffix, but they do not come with any particular confidence or compatibility guarantees.  We will be making a stable release as soon as is reasonable.

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect" % CatsEffectVersion
```

If your project uses Scala.js, replace the double-`%` with a triple.  Note that **cats-effect** has an upstream dependency on **cats-core** version 0.9.

Cross-builds are available for Scala 2.12, 2.11 and 2.10, Scala.js major version 0.6.

### Laws

The **cats-effect-laws** artifact provides [Discipline-style](https://github.com/typelevel/discipline) laws for the `Async`, `Sync` and `Effect` typeclasses (`LiftIO` is lawless, but highly parametric).  It is relatively easy to use these laws to test your own implementations of these typeclasses.  For an example of this, see [`IOTests.scala`](https://github.com/djspiewak/cats-effect/blob/master/laws/shared/src/test/scala/cats/effect/IOTests.scala).

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect-laws" % CatsEffectVersion % "test"
```

These laws are compatible with both Specs2 and ScalaTest.

### Versioning and Compatibility

Snapshots may be published to sonatype at any time.  They will be denoted as versions of the form `major.minor-hash`, where the `hash` is the 7 character git hash prefix of the commit from which the snapshot was published.  Thus, "snapshots" are in fact stable, and can be used as repeatable upstream dependencies if you're feeling courageous.  A snapshot with a `major` version of *x* and a `minor` version of *y* is expected (and indeed, machine-checked in so far as possible) to be binary compatible with the full release with version *x*.*y* (and all its subsequent minor versions).  Thus, eviction works basically the way you expect.  The only exception to this is *unreleased* incompatible versions, since such versions are still in flux and early snapshots may be incompatible with this future release.

Note that this implies that snapshots of a particular release line may be published *after* the main release.  You can find more details [here](https://github.com/typelevel/cats-effect/blob/563d29ee01885b00613a3dd8eb6f12c56aa126b2/build.sbt#L80-L110).

This project is intended to be an upstream dependency of a large swath of the ecosystem, similar in many respects to cats-core itself.  For that reason, binary compatibility and stability is *incredibly* important.  Breaking changes to major releases are going to be almost universally rejected, and will require extreme justification (and likely blood sacrifice) to obtain approval.

## API

Most of the API documentation can be found [in the scaladoc](https://www.javadoc.io/doc/org.typelevel/cats-effect_2.12).  To summarize though, the typeclass hierarchy looks something like this:

![cats-effect typeclasses](https://docs.google.com/drawings/d/1JIxtfEPKxUp402l8mYYDj7tDuEdEFAiqvJJpeAXAwG0/pub?w=1025&amp;h=852)

All of the typeclasses are of kind `(* -> *) -> *`, as you would expect.  `MonadError` is of course provided by [cats-core](https://github.com/typelevel/cats), while the other four classes are in cats-effect.  For concision and reference, the abstract methods of each typeclass are given below:

- `Sync[F]`
  + `def suspend[A](thunk: => F[A]): F[A]`
- `Async[F]`
  + `def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]`
- `LiftIO[F]`
  + `def liftIO[A](ioa: IO[A]): F[A]`
- `Effect[F]`
  + `def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]`

The `runAsync` function is of particular interest here, since it returns the *concrete* type `IO[Unit]`.  Effectively, what this type is asserting is that all effect-ish things must be able to asynchronous interpret into `IO`, which is the canonical parametric type for managing side-effecting computation.  `IO` in turn has several functions for (unsafely!) running its constituent actions *as* side effects, for interoperability with legacy effectful APIs and for ending the world.  Of course, concrete `Effect` implementations are free to define their own unsafe runner functions, and we expect that most of them will do exactly this.

Really, this type signature is saying that, ultimately, `IO` is side effects and side effects are `IO`, especially when taken together with the `liftIO` function.

### JavaScript and `unsafeRunSync()`

One of the trickiest functions to design from the perspective of `IO` is the `unsafeRunSync()` function, which has the very revealing (and terrifying) type signature `IO[A] => A`.  This function is extremely convenient for testing, as well as simplifying interoperability with legacy side-effecting APIs.  Unfortunately, it is also impossible to implement *safely* on JavaScript.

The reason for this is the presence of async actions.  For any `IO` constructed with the `async` function, we have to somehow extract the `A` (or the `Throwable`) which is received by the callback and move that value (or exception) *back* to the call-site for `unsafeRunSync()`.  This is exactly as impossible as it sounds.  On the JVM, we can use a `CountDownLatch` to simply block the thread, hoping that the `async` callback will eventually get fired and we will receive a result.  Unfortunately, on JavaScript, this would be impossible since there is only one thread!  If that thread is blocked awaiting a callback, then there is no thread on which to fire the callback.  So we would have a deadlock.

`IO` solves this problem by providing users with a lawful implementation of `runAsync`.  On a generic `Effect`, `runAsync` provides a means for asynchronously interpreting the effect into `IO`, a concrete type which can be run directly.  On `IO`, `runAsync` provides a means for asynchronously interpreting `IO` into *another* `IO` which is guaranteed to be synchronous.  Thus, it is safe – even on JavaScript! – to call `unsafeRunSync()` on the `IO` which results from `runAsync`.  Though, notably, `runAsync` returns an `IO[Unit]` rather than an `IO`.  So really calling `runAsync` on `IO` followed by `unsafeRunSync()`  is the same as calling `unsafeRunAsync`.

It would be quite weird if this were *not* the case.

### Testing and Timeouts

One of the four unsafe functions provided by `IO` is `unsafeRunTimed`.  This function takes a `scala.concurrent.duration.Duration` and uses that value to set a timeout when blocking for an asynchronous result.  *Critically*, this timeout does not come into play if the `IO` is entirely synchronous!  Nor is it fully used when the `IO` is partially synchronous, and partially asynchronous (constructed via `flatMap`).  Thus, the name is slightly misleading: it is *not* a timeout on the overall `IO`, it is a timeout on the thread blocking associated with awaiting any `async` action contained within the `IO`.

This function is useful for testing, and *no where* else!  It is not an appropriate function to use for implementing timeouts.  In fact, if you find this function *anywhere* in your production code path, you have a bug.  But it is *very* useful for testing.

If the timeout is hit, the function will return `None`.

### Stack-Safety

`IO` is stack-safe… to a point.  Any `IO` actions which are synchronous are entirely stack-safe.  In other words it is safe to `flatMap`, `attempt`, and otherwise futz with synchronous `IO` inside of loop-ish constructs, and the interpretation of that `IO` will use constant stack.  The implementation of `tailRecM`, from the cats `Monad`, reflects this fact: it just delegates to `flatMap`!

However, there's a catch: any `IO` constructed with `async` will *not* be stack-safe.  This is because `async` is fundamentally capturing a callback, and as the JVM does not support tail call elimination, it will always result in a new stack frame.  This problem is best illustrated with a unit test:

```scala
// this is expected behavior
test("fail to provide stack safety with repeated async suspensions") {
  val result = (0 until 10000).foldLeft(IO(0)) { (acc, i) =>
    acc.flatMap(n => IO.async[Int](_(Right(n + 1))))
  }

  intercept[StackOverflowError] {
    result.unsafeRunAsync(_ => ())
  }
}
```

What we're doing here is constructing 10000 `IO` instances using `async`, each nested within the `flatMap` of the previous one.  This will indeed blow the stack on any JVM with default parameters.  This is obviously not an issue for JavaScript, though this test does appear to use an enormous amount of memory on V8 (around 4 GB).

Anyway, this stack unsafety is by design.  Realistically, the only way to avoid this problem would be to thread-shift (via `ExecutionContext`) the results of any callback threaded through `async`.  This would effectively reset the stack, avoiding the uneliminated tail call problem.  However, this is not always what you want.  Some frameworks, such as Netty, can provide a performance benefit to keeping short continuations on the event dispatch pool, rather than thread-shifting back to the application main pool.  Other frameworks, such as [SWT](https://www.eclipse.org/swt/), outright *require* that callback continuations remain on the *calling* thread, and will throw exceptions if you attempt otherwise.

As `IO` is attempting to *not* be opinionated about applications or threading in general, it simply chooses not to thread-shift `async`.  If you need thread-shifting behavior, it is relatively easy to implement yourself, or you can simply use the built-in `shift` function.  The `shift` function takes an implicit `ExecutionContext` and moves as much of the `IO` as possible (its synchronous prefix and its asynchronous continuation) onto that thread pool.  If we were to modify the above test by inserting a call to `shift` *anywhere* in the body of the fold, the resulting `IO` would be stack-safe.

## Development

We use the standard pull request driven github workflow.  Pull requests are always welcome, even if it's for something as minor as a whitespace tweak!  If you're a maintainer, you are expected to do your work in pull requests, rather than pushing directly to master.  Ideally, someone other than yourself will merge and push your PR to master.  However, if you've received at least one explicit 👍 from another maintainer (or significant volume of 👍 from the general cats community), you may merge your own PR in the interest of moving forward with important efforts.  Please don't abuse this policy.

Do *not* rebase commits that have been PR'd!  That history doesn't belong to you anymore, and it is not yours to rewrite.  This goes for maintainers and contributors alike.  Rebasing locally is completely fine (and encouraged), since linear history is pretty and checkpoint commits are not.  Just don't rebase something that's already out there unless you've *explicitly* marked it as a work in progress (e.g. `[WIP]`) in some clear and unambiguous way.

cats-effect is a [Typelevel](http://typelevel.org/) project. This means we embrace pure, typeful, functional programming, and provide a safe and friendly environment for teaching, learning, and contributing as described in the Typelevel [Code of Conduct](http://typelevel.org/conduct.html).
