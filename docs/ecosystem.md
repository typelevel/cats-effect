---
id: ecosystem
title: Overview
---

A very large percentage of the Cats Effect ecosystem is built not only on top of Cats Effect itself, but additionally layers on top of [Fs2](https://fs2.io). Fs2 offers a number of major features which make it much easier to build real-world applications with extremely high performance and maintainability. Notably:

- A high-level concurrency model, built on top of fibers, which makes it even easier to build complex concurrent lifecycles
- High-performance and idiomatic filesystem and network socket (TCP and UDP) implementations
- Pervasive memory safety

While it is not *necessary* to use Fs2 in order to use Cats Effect, it is an extremely convenient and powerful library which brings a lot to the table, which is precisely why so much of the ecosystem builds upon it. In practice, many applications which *don't* leverage Fs2 will inevitably reinvent a lot of the same functionality by necessity.

```scala
libraryDependencies += "co.fs2" %% "fs2-core" % "3.0.0"
```

A simple example which uses the Fs2 filesystem APIs:

```scala
import cats.effect.{IO, IOApp}
import fs2.{text, Stream}
import fs2.io.file.Files
import java.nio.file.Paths

object Converter extends IOApp.Simple {

  val converter: Stream[IO, Unit] = {
    def fahrenheitToCelsius(f: Double): Double =
      (f - 32.0) * (5.0 / 9.0)

    Files[IO].readAll(Paths.get("testdata/fahrenheit.txt"), 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(Files[IO].writeAll(Paths.get("testdata/celsius.txt")))
  }

  val run = converter.compile.drain
}
```
