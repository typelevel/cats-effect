# Cats Effect

[![Build Status](https://travis-ci.org/typelevel/cats-effect.svg?branch=master)](https://travis-ci.org/typelevel/cats-effect) [![Gitter](https://img.shields.io/gitter/room/typelevel/cats-effect.svg)](https://gitter.im/typelevel/cats-effect) [![Latest version](https://index.scala-lang.org/typelevel/cats-effect/cats-effect/latest.svg?color=orange)](https://index.scala-lang.org/typelevel/cats-effect/cats-effect)

> For when purity just isn't impure enough.

This project aims to provide a standard `IO` type ([doc](https://typelevel.org/cats-effect/datatypes/io.html) / [api](https://typelevel.org/cats-effect/api/cats/effect/IO.html)) for the [Cats](http://typelevel.org/cats/) ecosystem, as well as a set of typeclasses (and associated laws!) which characterize general effect types. This project was *explicitly* designed with the constraints of the JVM and of JavaScript in mind. Critically, this means two things:

- Manages both synchronous *and* asynchronous (callback-driven) effects
- Compatible with a single-threaded runtime

In this way, `IO` is more similar to common `Task` implementations than it is to the classic `scalaz.effect.IO` or even Haskell's `IO`, both of which are purely synchronous in nature. As Haskell's runtime uses green threading, a synchronous `IO` (and the requisite thread blocking) makes a lot of sense. With Scala though, we're either on a runtime with native threads (the JVM) or only a single thread (JavaScript), meaning that asynchronous effects are every bit as important as synchronous ones.

## Usage

Versions of Cats Effect:

- Stable: `2.1.3`

See [compatibility and versioning](https://github.com/typelevel/cats-effect/blob/master/versioning.md) for more information on our compatibility and semantic versioning policies.

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.3"
```

Cats Effect relies on improved type inference and needs partial unification enabled as described in the Cats [Getting Started](https://github.com/typelevel/cats#getting-started) documentation.

If your project uses Scala.js, replace the double-`%` with a triple. Note that **cats-effect** has an upstream dependency on **cats-core** version 2.x.

Cross-builds are available for Scala 2.12.x and 2.13.x, with Scala.js builds targeting both 0.6.x and 1.0.x.

The most current snapshot (or major release) can be found in the maven badge at the top of this readme. If you are a very brave sort, you are free to depend on snapshots; they are stable versions, as they are derived from the git hash rather than an unstable `-SNAPSHOT` suffix, but they do not come with any particular confidence or compatibility guarantees.

Please see [this document](https://github.com/typelevel/cats-effect/blob/master/verifying-releases.md) for information on how to cryptographically verify the integrity of cats-effect releases. You should *absolutely* be doing this! It takes five minutes and eliminates the need to trust a third-party with your classpath.

### Laws

The **cats-effect-laws** artifact provides [Discipline-style](https://github.com/typelevel/discipline) laws for the `Sync`, `Async`, `Concurrent`, `Effect` and `ConcurrentEffect` typeclasses (`LiftIO` is lawless, but highly parametric). It is relatively easy to use these laws to test your own implementations of these typeclasses. Take a look [here](https://github.com/typelevel/cats-effect/tree/master/laws/shared/src/main/scala/cats/effect/laws) for more.

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect-laws" % "2.1.3" % "test"
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
| [Finch](https://finagle.github.io/finch/) | Scala combinator API for building Finagle HTTP services |
| [Http4s](http://http4s.org/) | Typeful, functional, streaming HTTP for Scala |
| [Monix](https://monix.io/) | Asynchronous, Reactive Programming for Scala and Scala.js |
| [Pure Config](https://pureconfig.github.io/) | A boilerplate-free library for loading configuration files |
| [Scala Cache](https://cb372.github.io/scalacache/) | A facade for the most popular cache implementations for Scala |
| [Sttp](http://sttp.readthedocs.io/en/latest/) | The Scala HTTP client you always wanted |

## Related Projects

These are some of the projects that provide high-level functions on top of `cats-effect`:

| Project | Description |
| ------- | ----------- |
| [Cats Retry](https://github.com/cb372/cats-retry) | A library for retrying actions that can fail |
| [Console4cats](https://console4cats.profunktor.dev/) | Console I/O for Cats Effect |
| [Fuuid](https://christopherdavenport.github.io/fuuid/) | Functional UUID's |
| [Linebacker](https://christopherdavenport.github.io/linebacker/) | Thread Pool Management for Scala: Enabling functional blocking where needed |
| [Log4cats](https://christopherdavenport.github.io/log4cats/) | Functional Logging |
| [Cats STM](https://timwspence.github.io/cats-stm/) | Software Transactional Memory for Cats Effect |
| [Mau](https://github.com/kailuowang/mau) | A tiny library for an auto polling `Ref` |
| [Odin](https://github.com/valskalla/odin) | Fast & Functional logger with own logging backend |
| [cats-effect-testing](https://github.com/djspiewak/cats-effect-testing) | Experimental integration between cats-effect and testing frameworks |
| [graphite4s](https://github.com/YannMoisan/graphite4s) | lightweight graphite client |

## Development

We use the standard pull request driven github workflow. Pull requests are always welcome, even if it's for something as minor as a whitespace tweak! If you're a maintainer, you are expected to do your work in pull requests, rather than pushing directly to master. Ideally, someone other than yourself will merge and push your PR to master. However, if you've received at least one explicit 👍 from another maintainer (or significant volume of 👍 from the general Cats community), you may merge your own PR in the interest of moving forward with important efforts. Please don't abuse this policy.

Do *not* rebase commits that have been PR'd! That history doesn't belong to you anymore, and it is not yours to rewrite. This goes for maintainers and contributors alike. Rebasing locally is completely fine (and encouraged), since linear history is pretty and checkpoint commits are not. Just don't rebase something that's already out there unless you've *explicitly* marked it as a work in progress (e.g. `[WIP]`) in some clear and unambiguous way.

cats-effect is a [Typelevel](http://typelevel.org/) project. This means we embrace pure, typeful, functional programming, and provide a safe and friendly environment for teaching, learning, and contributing as described in the [Code of Conduct].

### Contributing documentation

The sources for the cats-effect microsite can be found in `site/src/main/mdoc`.
The menu structure is in `site/src/main/resources/microsite/data/menu.yml`.

You can build the microsite with `sbt microsite/makeMicrosite`.

To preview your changes you need to have
[jekyll](https://github.com/jekyll/jekyll) installed. This depends on your
platform, but assuming you have ruby installed it could be as simple as `gem
install jekyll`.

Start a local server by navigating to `site/target/site`, then run `jekyll
serve`. Finally point your browser at
[http://localhost:4000/cats-effect/](http://localhost:4000/cats-effect/). Any
changes should be picked up immediately when you re-run `sbt
microsite/makeMicrosite`.

## License

```
Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers

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

[Code of Conduct]: https://github.com/typelevel/cats-effect/blob/master/CODE_OF_CONDUCT.md
