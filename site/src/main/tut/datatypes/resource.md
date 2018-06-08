---
layout: docsplus
title:  "Resource"
number: 14
source: "core/shared/src/main/scala/cats/effect/Resource.scala"
scaladoc: "#cats.effect.Resource"
---

Effectfully allocates and releases a resource. Forms a `MonadError` on the resource type when the effect type has a `Bracket` instance.

```tut:silent
import cats.effect.Bracket

abstract class Resource[F[_], A] {
  def allocate: F[(A, F[Unit])]
  
  def use[B, E](f: A => F[B])(implicit F: Bracket[F, E]): F[B] =
    F.bracket(allocate)(a => f(a._1))(_._2)
}
```

Nested resources are released in reverse order of acquisition. Outer resources are released even if an inner use or release fails.

### Example

```tut:silent
import cats.effect.{IO, Resource}
import cats.implicits._

def mkResource(s: String) = {
  val acquire = IO(println(s"Acquiring $s")) *> IO.pure(s)

  def release(s: String) = IO(println(s"Releasing $s"))

  Resource.make(acquire)(release)
}

val r = for {
  outer <- mkResource("outer")
  inner <- mkResource("inner")
} yield (outer, inner)

r.use { case (a, b) => IO(println(s"Using $a and $b")) }.unsafeRunSync
```

If using an AutoCloseable create a resource without the need of dealing with close

### Example

```tut:silent
    import cats.effect.Resource

    val acquire = IO {
      scala.io.Source.fromString("Hello world")
    }

    Resource.fromAutoClosable(acquire).use(source => IO.pure(println(source.mkString("")))).unsafeRunSync()
```

