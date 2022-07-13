---
id: faq
title: FAQ
---

## Scala CLI

[Scala CLI](https://scala-cli.virtuslab.org/) can run both `.sc` files and `.scala` files. `.sc` files allow definitions at the top level and a main method is synthesized to run it. Unfortunately this does not work well with `IO#unsafeRunSync`. You should put your cats-effect code inside the `run` method of an `IOApp` and save it as a `.scala` file instead.

```scala-cli
//> using scala "2.13.8"
//> using lib "org.typelevel::cats-effect::3.3.14"

import cats.effect._

object HelloWorld extends IOApp.Simple {
  val run: IO[Unit] = IO.println("Hello world")
}
```

```sh
$ scala-cli Hello.scala
Hello world
```

### Native Image Example

[Scala CLI](https://scala-cli.virtuslab.org/) can be leveraged to produce a native-image executable using the [package command](https://scala-cli.virtuslab.org/docs/commands/package#native-image):

```sh
$ scala-cli package --native-image --graalvm-version 22.1.0 -f Hello.scala -- --no-fallback
[...]
$ ./HelloWorld
Hello world
```

> Note: GraalVm Native Image > 21.0.0 and `--no-fallback` are mandatory: see [here](core/native-image.md) for details
