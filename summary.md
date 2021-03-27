## Summary

Here is a general view of the steps you should take to migrate your application to Cats Effect 3:

<!-- no toc - annotation for vscode plugin to stop making this a structured ToC -->
1. [Make sure your dependencies have upgraded](#make-sure-your-dependencies-have-upgraded)
2. [Run the Scalafix migration](#run-the-scalafix-migration) (optional)
3. [Upgrade dependencies and Cats Effect itself](#upgrade-dependencies)
4. [Fix remaining compilation issues](#fix-remaining-compilation-issues)
5. [Test your application](#test-your-application). <!-- todo wording -->

### Before you begin: this isn't a "quick start" guide

This guide is meant for existing users of Cats Effect 2 who want to upgrade their applications
to 3.0.0.

> If you haven't used Cats Effect before and want to give it a try,
> please follow the [getting started guide](./getting-started.html) instead!

### Need help?

If any point of the migration turns out to be difficult and you feel like you need help, feel free to [explain your problem on Gitter](https://gitter.im/typelevel/cats-effect) and we will do our best to assist you.
If you spot a mistake in the guide, you can [report an issue on GitHub](https://github.com/typelevel/cats-effect/issues/new).

## Make sure your dependencies have upgraded

Before you make any changes to your build or your code, you should make sure all of your direct and transitive dependencies have made releases compatible with Cats Effect 3.

There isn't any automated way to do this, but you can just go ahead and [try to upgrade the dependencies](#upgrade-dependencies), then stash the changes and return to here.

If you're using an open source library that hasn't made a compatible release yet, [let us know - we are keeping track of the efforts of library authors](https://github.com/typelevel/cats-effect/issues/1330) to publish compatible releases as soon as possible when 3.0.0 final is out.

## Run the Scalafix migration

Many parts of this migration can be automated by using the [Scalafix][scalafix] migration.

> Note: In case of projects using Scala Steward, the migration should automatically be applied when you receive the update.

If you want to trigger the migration manually, you can follow [the instructions here](https://github.com/typelevel/cats-effect/blob/series/3.x/scalafix/README.md). Remember to run it *before* making any changes to your dependencies' versions.

Now is the time to update cats-effect **every dependency using it** to a CE3-compatible version.

## Upgrade dependencies

At this point, if you've run the Scalafix migration, your code will not compile. However, you should hold off going through the list of errors and fixing the remaining issues yourself at this point.

If you're an [sbt][sbt] user, it is recommended that you upgrade to at least `1.5.0-RC1` before you proceed:

In your `project/build.properties`:

```diff
- sbt.version = 1.4.9
+ sbt.version = 1.5.0-RC1
```

This will enable eviction errors, which means your build will only succeed if all your dependencies
use compatible versions of each library (in the case of cats-effect, this will require your dependencies
all use either the 2.x.x versions or the 3.x.x versions).

Having upgraded sbt, you can try to upgrade cats-effect:

### Which modules should I use?

Cats Effect 3 splits the code dependency into multiple modules. If you were previously using `cats-effect`, you can keep doing so, but if you're a user of another effect system (Monix, ZIO, ...), or a library author, you might be able to depend on a subset of it instead.

The current non-test modules are:

```scala
"org.typelevel" %% "cats-effect-kernel"         % "3.0.0-RC1",
"org.typelevel" %% "cats-effect-std"            % "3.0.0-RC1"
"org.typelevel" %% "cats-effect"                % "3.0.0-RC1",
```

- `kernel` - type class definitions, simple concurrency primitives
- `std` - high-level abstractions like `Console`, `Semaphore`, `Hotswap`, `Dispatcher`
- `core` - `IO`, `SyncIO`

```diff
libraryDependencies ++= Seq(
  //...
-  "org.typelevel" %% "cats-effect" % "2.4.0",
+  "org.typelevel" %% "cats-effect" % "3.0.0",
  //...
)
```

Then run `update` or `evicted` in sbt. You should see something like the following:

```scala
sbt:demo> update
[error] stack trace is suppressed; run last core / update for the full output
[error] (core / update) found version conflict(s) in library dependencies; some are suspected to be binary incompatible:
[error]
[error] 	* org.typelevel:cats-effect_2.13:3.0.0 (early-semver) is selected over {2.3.1, 2.1.4}
[error] 	    +- com.example:core-core_2.13:0.0.7-26-3183519d       (depends on 3.0.0)
[error] 	    +- io.monix:monix-catnap_2.13:3.3.0                   (depends on 2.1.4)
[error] 	    +- com.github.valskalla:odin-core_2.13:0.11.0         (depends on 2.3.1)
[error]
[error]
[error] this can be overridden using libraryDependencySchemes or evictionErrorLevel
[error] Total time: 0 s, completed 27 Mar 2021, 17:51:52
```

This tells you that you need to upgrade both `monix-catnap` and `odin-core` before proceeding. Make sure `update` of all your project's modules passes before proceeding to the next point.

> Note that some of the libraries listed might be transitive dependencies, which means
> you're depending on other projects that depend on them.
> Upgrading your direct dependencies should solve the transitive dependencies' incompatibilities as well.

## Fix remaining compilation issues

<!-- todo -->

## Test your application

If you followed this guide, all your dependencies are using the 3.x releases of cats-effect, your code compiles and your tests pass,
the process is probably done - at this point you should do the usual steps you make after major changes in your application:
running integration/end to end tests, manual testing, canary deployments and any other steps that might
typically be done in your environment.

Enjoy using cats-effect!

[sbt]: https://scala-sbt.org
[scalafix]: https://scalacenter.github.io/scalafix/
