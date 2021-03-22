---
layout: docsplus
title:  "Testing with cats-effect"
position: 2
---

<nav role="navigation" id="toc"></nav>

## Compatible libraries

Relatively few libraries support cats-effect directly at this time. However, most (if not all) popular testing frameworks have libraries offering some level of integration

- [cats-effect-testing](https://github.com/djspiewak/cats-effect-testing): Supports Scalatest, Specs2, munit, minitest, µTest, and scalacheck
- [distage-testkit](https://izumi.7mind.io/distage/distage-testkit): Supported natively
- [@Daenyth's IOSpec gist](https://gist.github.com/Daenyth/67575575b5c1acc1d6ea100aae05b3a9) for scalatest (library pending)
- [munit-cats-effect](https://github.com/typelevel/munit-cats-effect)
- [weaver-test](https://disneystreaming.github.io/weaver-test/): Supported natively

## Property-based Testing

### Scalacheck

Scalacheck primarily supports properties in the shape `A => Assertion`.
To support writing effectful properties with the shape `A => F[Assertion]`, you can use one of these tools:

- [scalacheck-effect](https://github.com/typelevel/scalacheck-effect)
- [cats-effect-testing](https://github.com/djspiewak/cats-effect-testing) - though note that this doesn't support "true" async, as it does block threads under the hood.

You might also want to use [cats-scalacheck](https://christopherdavenport.github.io/cats-scalacheck/), which provides instances of `cats-core` typeclasses.

## Best practices

Avoid using `unsafe*` methods in tests, the same as you'd avoid them in "main" code.
Writing tests to be structured the same way as "normal" code results in tests that are less likely to be flaky, act as executable documentation, and remain easy to read.

Use a compatible framework's support for writing `IO[Assertion]` style tests.

### Testing concurrency

In many test cases, `TestContext` should be used as an `ExecutionContext`, `ContextShift`, and `Timer`. This gives you very precise control over the sequencing and scheduling of evaluation, making it possible to deterministically replicate certain race conditions or even identify timing-related bugs without having to rely on non-deterministic thread ordering. `TestContext` also gives you control over *time* (as exposed by `Timer` and `Clock`), which makes it easy to write unit tests for time-related semantics without having to rely on `sleep`s or other hacks.

To simulate time passing in a test, use `Timer[F].sleep(duration)`, which will defer to `TestContext`.

Be aware though that `TestContext` is a very artificial environment, and it can in turn mask bugs that a realistic executor would uncover. The most comprehensive test suites will often use a balance of both `TestContext` and a more real-world thread pool implementation.

Some reference material on this approach:

- [TestContext api documentation](https://www.javadoc.io/doc/org.typelevel/cats-effect-laws_2.13/2.2.0/cats/effect/laws/util/TestContext.html) (includes examples and motivation)
- [Time Traveling in Tests with Cats-Effect](https://blog.softwaremill.com/time-traveling-in-tests-with-cats-effect-b22084f6a89), by Krzysztof Ciesielski

### Managing shared resources

Sometimes you'll want to write a test that acquires a Resource before the suite and releases it after. For example, spinning up a database.

With the Weaver test framework, this is supported [using cats-effect `Resource`](https://disneystreaming.github.io/weaver-test/docs/resources) out of the box.

[distage-testkit](https://izumi.7mind.io/distage/distage-testkit) test framework extends the usefulness of `Resource` further, allowing to designate resources to be acquired only once globally for all test suites or for a subset of test suites. ([Resource Reuse - Memoization](https://izumi.7mind.io/latest/snapshot/distage/distage-testkit.html#resource-reuse-memoization))

For other test frameworks that use imperative "hook"-style methods (such as scalatest's `BeforeAndAfterAll` mixin), you can use [`allocated`](https://typelevel.org/cats-effect/api/cats/effect/Resource.html#allocated[G[x]%3E:F[x],B%3E:A](implicitF:cats.effect.Bracket[G,Throwable]):G[(B,G[Unit])])

```scala mdoc:invisible
import cats.effect._
```

```scala mdoc:compile-only
class Database {
    def close(): Unit = ???
}

object Database {
  def resource: Resource[IO, Database] = Resource.make(IO(new Database))(d => IO(d.close()))
}

class TestSuite {
  private var _database: Option[(Database, IO[Unit])] = None
  private def database: Database = _database.getOrElse(sys.error("not currently alive!"))._1

  def beforeAll: Unit = {
    _database = Some(Database.resource.allocated.unsafeRunSync())
    ()
  }

  def afterAll: Unit = {
    _database.foreach(_._2.unsafeRunSync())
    _database = None
  }

  /* tests using `database` */
  def test = {
      assert(database != null)
  }
}
```
