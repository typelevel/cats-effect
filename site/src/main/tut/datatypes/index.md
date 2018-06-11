---
layout: docs
title:  "Data Types"
position: 1
---

# Data Types

- **[IO](./io.html)**: data type for encoding side effects as pure values.
- **[Fiber](./fiber.html)**: the pure result of a [Concurrent](../typeclasses/concurrent.html) data type being started concurrently and that can be either joined or canceled.
- **[Resource](./resource.html)**: resource management data type that complements the `Bracket` typeclass.
- **[Timer](./timer.html)**: a pure scheduler exposing operations for measuring time and for delaying execution.
- **[IOApp](./ioapp.html)**: a safe application type that describes a main which executes a `cats.effect.IO`, as an entry point to a pure FP program.
- **[Console](./console.html)**: data type for read and write from/to the standard input/output stream.
