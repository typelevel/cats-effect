---
id: supervisor
title: Supervisor
---

## Motivation

There are multiple ways to spawn a fiber to run an action:

`Spawn[F]#start`: start and forget, no lifecycle management for the spawned fiber 

`Concurrent[F]#background`: ties the lifecycle of the spawned fiber to that of the fiber that invoked `background`

But what if we want to spawn a fiber that should outlive the scope that created
it, but we still want to control its lifecycle?

## Supervisor

A supervisor spawns fibers whose lifecycles are bound to that of the supervisor.

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

