---
layout: docs
title:  "Type Classes"
position: 2
---

# Type Classes

The following graphic represents the current hierarchy of `Cats Effect`:

![cats-effect typeclasses](../img/cats-effect-typeclasses.svg)

`MonadError` belongs in the [Cats](https://typelevel.org/cats/) project whereas the rest of the typeclasses belong in `Cats Effect`. On the side menu, you'll find more information about each of them, including examples.

## Cheat sheet

{:.responsive-pic}
![typeclasses cheat sheet](../img/typeclasses-cheat-sheet.png)

### Bracket
Can safely acquire and release resources

```scala
def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: A => F[Unit]): F[B]
```

### LiftIO
Can convert any given IO[A] into F[A]. Useful for defining parametric signatures and composing monad transformer stacks

```scala
def liftIO[A](ioa: IO[A]): F[A]
```

### Sync
Can suspend(describe) synchronous side-effecting code in F. But can't evaluate(run) it

```scala
def delay[A](thunk: => A): F[A]
```

### Async
Can suspend synchronous/asynchronous side-effecting code in F. Can describe computations, that may be executed independent of the main program flow (on another thread or machine)

```scala
def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
```

### Concurrent
Can concurrently start or cancel the side-effecting code in F

```scala
def start[A](fa: F[A]): F[Fiber[F, A]]
def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]]
def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[F]): F[A]
```

```scala
trait Fiber[F[_], A] {
  def cancel: F[Unit]
  def join: F[A]
}
```

### Effect
Allows lazy and potentially asynchronous evaluation of side-effecting code in F

```scala
def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit]
```

### ConcurrentEffect
Allows cancelable and concurrent evaluation of side-effecting code in F

```scala
def runCancelable[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[F]]
```
