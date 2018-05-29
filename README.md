# cats-effect 

[![Build Status](https://travis-ci.org/typelevel/cats-effect.svg?branch=master)](https://travis-ci.org/typelevel/cats-effect) [![Gitter](https://img.shields.io/gitter/room/typelevel/cats-effect.svg)](https://gitter.im/typelevel/cats-effect) [![Latest version](https://index.scala-lang.org/typelevel/cats-effect/cats-effect/latest.svg?color=orange)](https://index.scala-lang.org/typelevel/cats-effect/cats-effect) [![Coverage Status](https://codecov.io/gh/typelevel/cats-effect/coverage.svg?branch=master)](https://codecov.io/gh/typelevel/cats-effect?branch=master)

> For when purity just isn't impure enough.

This project aims to provide a standard `IO` type ([doc](https://typelevel.org/cats-effect/datatypes/io.html) / [api](https://typelevel.org/cats-effect/api/cats/effect/IO.html)) for the [Cats](http://typelevel.org/cats/) ecosystem, as well as a set of typeclasses (and associated laws!) which characterize general effect types.  This project was *explicitly* designed with the constraints of the JVM and of JavaScript in mind.  Critically, this means two things:

- Manages both synchronous *and* asynchronous (callback-driven) effects
- Compatible with a single-threaded runtime

In this way, `IO` is more similar to common `Task` implementations than it is to the classic `scalaz.effect.IO` or even Haskell's `IO`, both of which are purely synchronous in nature.  As Haskell's runtime uses green threading, a synchronous `IO` (and the requisite thread blocking) makes a lot of sense.  With Scala though, we're either on a runtime with native threads (the JVM) or only a single thread (JavaScript), meaning that asynchronous effects are every bit as important as synchronous ones.

## Usage

Versions of Cats Effect:

- Old `0.x` series: `0.10.1`
- Current: `1.0.0-RC`
- Experimental: `1.0.0-RC2-68b7bd9`

See [compatibility and versioning](https://github.com/typelevel/cats-effect/blob/master/versioning.md) for more information on our compatibility and semantic versioning policies.

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0-RC"
```

If your project uses Scala.js, replace the double-`%` with a triple.  Note that **cats-effect** has an upstream dependency on **cats-core** version 1.x.

Cross-builds are available for Scala 2.12 and 2.11, Scala.js major version 0.6.x.

The most current snapshot (or major release) can be found in the maven badge at the top of this readme.  If you are a very brave sort, you are free to depend on snapshots; they are stable versions, as they are derived from the git hash rather than an unstable `-SNAPSHOT` suffix, but they do not come with any particular confidence or compatibility guarantees.

Please see [this document](https://github.com/typelevel/cats-effect/blob/master/verifying-releases.md) for information on how to cryptographically verify the integrity of cats-effect releases.  You should *absolutely* be doing this!  It takes five minutes and eliminates the need to trust a third-party with your classpath.

### Laws

The **cats-effect-laws** artifact provides [Discipline-style](https://github.com/typelevel/discipline) laws for the `Sync`, `Async`, `Concurrent`, `Effect` and `ConcurrentEffect` typeclasses (`LiftIO` is lawless, but highly parametric).  It is relatively easy to use these laws to test your own implementations of these typeclasses. Take a look [here](https://github.com/typelevel/cats-effect/tree/master/laws/shared/src/main/scala/cats/effect/laws) for more.

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect-laws" % "1.0.0-RC" % "test"
```

These laws are compatible with both Specs2 and ScalaTest.

## Documentation

Links:

1. Website: [typelevel.org/cats-effect/](https://typelevel.org/cats-effect/)
2. ScalaDoc: [typelevel.org/cats-effect/api/](https://typelevel.org/cats-effect/api/)

Related Cats links (the core):

1. Website: [typelevel.org/cats/](https://typelevel.org/cats/)
2. ScalaDoc: [typelevel.org/cats/api/](https://typelevel.org/cats/api/)

## Libraries

These are some well known libraries that depend on `cats-effect`:

| Project | Description |
| ------- | ----------- |
| [Ciris](https://cir.is) | Lightweight, extensible, and validated configuration loading in Scala |
| [Doobie](http://tpolecat.github.io/doobie/) | A principled JDBC layer for Scala |
| [Eff](http://atnos-org.github.io/eff/) | Extensible Effects for Scala |
| [Fs2](https://functional-streams-for-scala.github.io/fs2/) | Functional Streams for Scala (Streaming I/O library) |
| [Http4s](http://http4s.org/) | Typeful, functional, streaming HTTP for Scala |
| [Monix](https://monix.io/) | Asynchronous, Reactive Programming for Scala and Scala.js |
| [Pure Config](https://pureconfig.github.io/) | A boilerplate-free library for loading configuration files |
| [Scala Cache](https://cb372.github.io/scalacache/) | A facade for the most popular cache implementations for Scala |
| [Sttp](http://sttp.readthedocs.io/en/latest/) | The Scala HTTP client you always wanted |

## Development

We use the standard pull request driven github workflow.  Pull requests are always welcome, even if it's for something as minor as a whitespace tweak!  If you're a maintainer, you are expected to do your work in pull requests, rather than pushing directly to master.  Ideally, someone other than yourself will merge and push your PR to master.  However, if you've received at least one explicit 👍 from another maintainer (or significant volume of 👍 from the general cats community), you may merge your own PR in the interest of moving forward with important efforts.  Please don't abuse this policy.

Do *not* rebase commits that have been PR'd!  That history doesn't belong to you anymore, and it is not yours to rewrite.  This goes for maintainers and contributors alike.  Rebasing locally is completely fine (and encouraged), since linear history is pretty and checkpoint commits are not.  Just don't rebase something that's already out there unless you've *explicitly* marked it as a work in progress (e.g. `[WIP]`) in some clear and unambiguous way.

cats-effect is a [Typelevel](http://typelevel.org/) project. This means we embrace pure, typeful, functional programming, and provide a safe and friendly environment for teaching, learning, and contributing as described in the Typelevel [Code of Conduct](http://typelevel.org/conduct.html).

## License

```
Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
