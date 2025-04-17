---
id: scala-native
title: Scala Native
---

Cats Effect supports [Scala Native](https://github.com/scala-native/scala-native) since `3.3.14`.

## Scala Native example

Here's an example of a Hello world project compiled to a native executable
using [sbt-scala-native plugin](https://github.com/scala-native/scala-native).

```scala
// project/plugins.sbt
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.7")

// build.sbt
ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "2.13.8"

lazy val root = project.in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name                := "cats-effect-3-hello-world",
    libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.5.7",
    Compile / mainClass := Some("com.example.Main")
 )

// src/main/scala/com/example/Main.scala
package com.example

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  def run: IO[Unit] = IO.println("Hello Cats Effect!")
}
```

The code can be compiled using `sbt nativeLink` and a native executable can then
be found under `target/scala-2.13/cats-effect-3-hello-world-out`, and executed as any native
executable with the benefit of a really fast startup time ([hyperfine](https://github.com/sharkdp/hyperfine)
is a command line benchmarking tool written in Rust)

```sh
$ ./target/scala-2.13/cats-effect-3-hello-world-out
Hello Cats Effect!

$ hyperfine ./target/scala-2.13/cats-effect-3-hello-world-out
Benchmark 1: ./target/native-image/cats-effect-3-hello-world-out
  Time (mean ± σ):       7.5 ms ±   1.2 ms    [User: 3.6 ms, System: 2.3 ms]
  Range (min … max):     6.0 ms …  17.9 ms    141 runs
```

Another way to get your cats effect app compiled to a native executable is to leverage
the package command of scala-cli, like in the [example](../faq.md#Scala-Native-Example)

## Limitations

The [Scala Native](https://github.com/scala-native/scala-native) runtime is [single-threaded](https://scala-native.org/en/latest/user/lang.html#multithreading), similarly to ScalaJS. That's why the `IO#unsafeRunSync` is not available.
Be careful with `IO.blocking(...)` as it blocks the thread since there is no dedicated blocking thread pool.
For more in-depth details, see the [article](https://typelevel.org/blog/2022/09/19/typelevel-native.html#how-does-it-work) with explanations of how the Native runtime works.

## Showcase projects

- [scala-native-ember-example](https://github.com/ChristopherDavenport/scala-native-ember-example) shows how you can run the [http4s](https://github.com/http4s/http4s) server as a native binary
