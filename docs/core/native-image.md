---
id: native-image
title: GraalVM Native Image
---

Cats Effect 3.3.0 brought support for printing [a fiber dump](./fiber-dumps.md)
to the standard error stream.
This functionality can be triggered using POSIX signals. Unfortunately,
due to the `sun.misc.Signal` API being an unofficial JDK API, in order to
achieve maximum compatibility, this functionality was implemented using the
[`java.lang.reflect.Proxy`](https://docs.oracle.com/javase/8/docs/api/)
reflective approach.

Luckily, GraalVM Native Image has full support for both `Proxy` and POSIX
signals. Cats Effect jars contain extra metadata that makes building native
images seamless, without the need of extra configuration. The only caveat
is that this configuration metadata is specific to GraalVM 21.0 and later.
Previous versions of GraalVM are supported, but Native Image support requires
at least 21.0.

## Native Image example

Here's an example of an Hello world project compiled with GraalVM Native Image
using [sbt-native-image plugin](https://github.com/scalameta/sbt-native-image).

```scala
// project/plugins.sbt
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.3.1")

// build.sbt
ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "2.13.8"

lazy val root = (project in file(".")).enablePlugins(NativeImagePlugin).settings(
  name                := "cats-effect-3-hello-world",
  libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.7",
  Compile / mainClass := Some("com.example.Main"),
  nativeImageOptions  += "--no-fallback",
  nativeImageVersion  := "22.1.0" // It should be at least version 21.0.0
)

// src/main/scala/com/example/Main.scala
package com.example

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  def run: IO[Unit] = IO.println("Hello Cats Effect!")
}
```

> Note: `nativeImageOptions += "--no-fallback"` is required, otherwise native-image will try searching a static reflection configuration for [this Enumeration method](https://github.com/scala/scala/blob/v2.13.8/src/library/scala/Enumeration.scala#L190-L215=). Thus using this flag is safe only if you're not using `Enumeration`s in your codebase, see [this comment](https://github.com/typelevel/cats-effect/issues/3051#issuecomment-1167026949) for more info.

The code can be compiled using `sbt nativeImage` and a native-image executable can then
be found under `target/native-image/cats-effect-3-hello-world`, and executed as any native
executable with the benefit of a really fast startup time ([hyperfine](https://github.com/sharkdp/hyperfine)
is a command line benchmarking tool written in Rust)

```sh
$ ./target/native-image/cats-effect-3-hello-world
Hello Cats Effect!

$ hyperfine ./target/native-image/cats-effect-3-hello-world
Benchmark 1: ./target/native-image/cats-effect-3-hello-world
  Time   (mean ± σ):    11.2 ms ±   0.6 ms    [User: 6.7 ms, System: 4.8 ms]
  Range (min … max):     9.4 ms …  12.9 ms    182 runs
```

Another way to get your cats effect app compiled with native-image is to leverage
the package command of scala-cli, like in the [example](../faq.md#Native-Image-Example)
