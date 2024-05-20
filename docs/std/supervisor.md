---
id: supervisor
title: Supervisor
---

## Motivation

[Spawn](../typeclasses/spawn.md) provides multiple ways to spawn a [fiber](../concepts.md#fibers) to run an action:

`Spawn[F]#start`: start and forget, no lifecycle management for the spawned fiber 

`Spawn[F]#background`: ties the lifecycle of the spawned fiber to that of the fiber that invoked `background`

But what if we want to spawn a fiber that should outlive the scope that created
it, but we still want to control its lifecycle?

## Supervisor

A supervisor spawns fibers whose lifecycles are bound to that of the supervisor.

```scala
trait Supervisor[F[_]] {
  def supervise[A](fa: F[A]): F[Fiber[F, Throwable, A]]
}

object Supervisor {
  def apply[F[_]](await: Boolean)(implicit F: Concurrent[F]): Resource[F, Supervisor[F]]
}
```

Any fibers created via the supervisor will be finalized when the supervisor itself
is finalized via `Resource#use`.

There are two finalization strategies according to the `await` parameter of the constructor:
- `true` - wait for the completion of the active fibers
- `false` - cancel the active fibers

**Note:** if an effect that never completes is supervised by a `Supervisor` with the awaiting 
termination policy, the termination of the `Supervisor` is indefinitely suspended:
```scala mdoc:silent
import cats.effect.IO
import cats.effect.std.Supervisor

Supervisor[IO](await = true).use { supervisor =>
  supervisor.supervise(IO.never).void
}
```
