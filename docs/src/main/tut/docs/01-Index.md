---
layout: docs
title: Index
---

## {{ page.title }}

Here's some Scala code.

```tut
import cats._, cats.implicits._, cats.effect._
val a = IO(println("hoochie mama"))
(a *> a *> a).unsafeRunSync
```
