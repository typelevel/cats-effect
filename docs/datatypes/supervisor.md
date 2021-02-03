---
id: supervisor
title: supervisor
---

A `Supervisor` allows us to spawn fibers whose lifecycle is bound to that
of the supervisor. This is useful when we want the lifecycle of a fiber
to outlive the scope that created it.

```scala
trait Supervisor[F[_]] {

  def supervise[A](fa: F[A]): F[Fiber[F, Throwable, A]]
}

object Supervisor {
  def apply[F[_]](implicit F: Concurrent[F]): Resource[F, Supervisor[F]]
}
```

Any fibers created via the supervisor will be finalized when the supervisor itself
is finalized via `Resource#use`.

It's worth contrasting this behaviour with
`Spawn[F]#start`: start and forget, no lifecycle management for the spawned fiber 
`Concurrent[F]#background`: ties the lifecycle of the spawned fiber to that of the fiber that invoked `background`

