---
id: monadcancel
title: MonadCancel
---

A fiber can terminate in three different states, reflected by the different subtypes of `Outcome`:

```scala
sealed trait Outcome[F[_], E, A]
final case class Succeeded[F[_], E, A](fa: F[A]) extends Outcome[F, E, A]
final case class Errored[F[_], E, A](e: E) extends Outcome[F, E, A]
final case class Canceled[F[_], E, A]() extends Outcome[F, E, A]
```

This means that when writing resource-safe code, we have to worry about
cancelation as well as exceptions. The `MonadCancel` typeclass addresses this
by extending `MonadError` (in Cats) to provide the capability to guarantee the
running of finalizers when a fiber is canceled. Using it, we can define effects
which safely acquire and release resources:

```scala
import cats.effect._
import cats.effect.syntax.all._

openFile.bracket(fis => readFromFile(fis))(fis => closeFile(fis))
```

The `bracket` combinator works a bit like the FP equivalent of `try`/`finally`: if `openFile` runs (in the above), then `closeFile` *will* be run, no matter what. This will happen even if `readFromFile` produces an error, or even if the whole process is canceled by some other fiber. Additionally, `openFile` itself is atomic: it either doesn't evaluate at all (e.g. if the current fiber is canceled prior to any of this even happening), or it *fully* evaluates. This allows `openFile` to do complicated things in the process of acquiring the resource without fear of something external getting in the way.

In addition to `bracket`, `MonadCancel` also provides a lower-level operation, `uncancelable`, which makes it possible to perform extremely complex, cancelation-sensitive actions in a safe and composable manner. For example, imagine that we have a block of code which must be guarded by a `Semaphore`, ensuring the runtime has exclusive access when evaluating. The problem here is that the acquisition of the `Semaphore`, which is a resource, may *also* result in blocking the fiber, and thus may need to be canceled externally. Put another way: resource acquisition needs to be uncancelable, but this *particular* resource acquisition has a very specific point at which it needs to allow cancelation, otherwise it might end up locking up the JVM. `uncancelable` provides a mechanism to achieve this:

```scala mdoc
import cats.effect.{MonadCancel}
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.syntax.all._

def guarded[F[_], R, A, E](
    s: Semaphore[F],
    alloc: F[R])(
    use: R => F[A])(
    release: R => F[Unit])(
    implicit F: MonadCancel[F, E])
    : F[A] =
  F uncancelable { poll =>
    for {
      r <- alloc

      _ <- poll(s.acquire).onCancel(release(r))
      releaseAll = s.release >> release(r)

      a <- poll(use(r)).guarantee(releaseAll)
    } yield a
  }
```

The above looks intimidating, but it's actually just another flavor of `bracket`, the operation we saw earlier! The whole thing is wrapped in `uncancelable`, which means we don't need to worry about other fibers interrupting us in the middle of each of these actions. In particular, the very *first* action we perform is `alloc`, which allocates a value of type `R`. Once this has completed successfully, we attempt to acquire the `Semaphore`. If some other fiber has already acquired the lock, this may end up blocking for quite a while. We *want* other fibers to be able to interrupt this blocking, so we wrap the acquisition in `poll`.

You can think of `poll` like a "cancelable" block: it re-enables cancelation for whatever is inside of it, but *only* if that's possible. There's a reason it's not just called `cancelable`! We'll come back to this a little bit later.

If the semaphore acquisition is canceled, we want to make sure we still release the resource, `r`, so we use `onCancel` to achieve this. From that moment forward, if and when we're ready to release our resource, we want to *also* release the semaphore, so we create an effect which merges these two things together.

Finally we move on to invoking the `use(r)` action, which again we need to wrap in `poll` to ensure that it can be interrupted by an external fiber. This `use(r)` action may take quite a long time and have a lot of complicated internal workings, and the last thing we want to do is suppress cancelation for its entire duration. It's likely safe to cancel `use`, because the resource management is already handled (by us!).

Finally, once `use(r)` is done, we run `releaseAll`. The `guarantee` method does exactly what it sounds like it does: ensures that `releaseAll` is run regardless of whether `use(r)` completes naturally, raises an error, or is canceled.

As mentioned earlier, `poll` is *not* the same as a function hypothetically named `cancelable`. If we had something like `cancelable`, then we would need to answer an impossible question: what does `uncancelable` mean if someone can always contradict you with `cancelable`? Additionally, whatever answer you come up with to *that* question has to be applied to weird and complicated things such as `uncancelable(uncancelable(cancelable(...)))`, which is unpleasant and hard to define.

At the end of the day, the better option is to simply add `poll` to `uncancelable` after the fashion we have done. The *meaning* of `poll` is that it "undoes" the effect of its origin `uncancelable`. So in other words:

```scala
val fa: F[A] = ???
MonadCancel[F].uncancelable(poll => poll(fa))  // => fa
```

The `poll` wrapped around the `fa` effectively *eliminates* the `uncancelable`, so the above is equivalent to just writing `fa`. This significance of this "elimination" semantic becomes apparent when you consider what happens when multiple `uncancelable`s are nested within each other:

```scala
MonadCancel[F] uncancelable { outer =>
  MonadCancel[F] uncancelable { inner =>
    inner(fa)
  }
}
```

The `inner` poll eliminates the inner `uncancelable`, but the *outer* `uncancelable` still applies, meaning that this whole thing is equivalent to `MonadCancel[F].uncancelable(_ => fa)`. Indeed these
must be the semantics to preserve local reasoning about cancelation. Suppose `poll` had the effect
of making its interior cancelable. Then if we had
```scala
def foo[A](inner: IO[A]) = IO.uncancelable( _ =>
  // Relies on inner being uncancelable so we can clean up the resource after
  inner <* cleanupImportantResource
)

def bar: IO[String] = IO.uncancelable( poll =>
  poll(IO.pure("bar"))
)

val x: IO[String] = foo(bar)
```

If we inline the execution we see that we have:
```scala
val x: IO[String] = IO.uncancelable( _ =>
  IO.uncancelable( poll =>
    //Oh dear! If poll makes us cancelable then we could be canceled here
    //and never clean up the resource
    poll(IO.pure("bar"))
  ) <* cleanupImportantResource
)
```

Note that polling for an *outer* block within an inner one has no effect whatsoever. For example:

```scala
MonadCancel[F] uncancelable { outer =>
  MonadCancel[F] uncancelable { _ =>
    outer(fa)
  }
}
```

This is exactly the same as:

```scala
MonadCancel[F] uncancelable { _ =>
  MonadCancel[F] uncancelable { _ =>
    fa
  }
}
```

The use of the `outer` poll within the inner `uncancelable` is simply ignored unless we have already stripped off the inner `uncancelable` using *its* `poll`. For example:

```scala
MonadCancel[F] uncancelable { outer =>
  MonadCancel[F] uncancelable { inner =>
    inner(outer(fa))
  }
}
```

This is simply equivalent to writing `fa`. Which is to say, `poll` composes in the way you would expect.

They are applied to `fa` outermost-first as when the program is interpreted this order is reversed: the `inner` `poll` will be evaluated first, which removes the inner `uncancelable`, followed by the `outer` `poll`, which removes the outer `uncancelable` and allows cancelation. 

### Self-Cancelation

One particularly unique aspect of `MonadCancel` is the ability to self-cancel. For example:

```scala
MonadCancel[F].canceled >> fa
```

The above will result in a canceled evaluation, and `fa` will never be run, *provided* that it isn't wrapped in an `uncancelable` block. If the above is wrapped in `uncancelable`, then the `canceled` effect will be ignored.

Self-cancelation is somewhat similar to raising an error with `raiseError` in that it will short-circuit evaluation and begin "popping" back up the stack until it hits a handler. Just as `raiseError` can be observed using the `onError` method, `canceled` can be observed using `onCancel`.

The primary differences between self-cancelation and `raiseError` are two-fold. First, `uncancelable` suppresses `canceled` within its body unless `poll`ed. For this reason, `canceled` has a return type of `F[Unit]` and not `F[Nothing]`:

```scala
IO.uncancelable { poll =>
  val cancelation: IO[Unit] = IO.canceled
  cancelation.flatMap { x =>
    IO.println(s"This will print, meaning $x is not a Nothing")
  }
}
```

In this case, `canceled` is equivalent to just `().pure[F]`. Note however that cancelation will be observed as soon as `uncancelable` terminates, i.e. `uncancelable` only suppresses the cancelation until the end of its body, not indefinitely.

```scala mdoc
import cats.effect.IO
import cats.effect.unsafe.implicits.global

val run = for {
  fib <- (IO.uncancelable(_ =>
      IO.canceled >> IO.println("This will print as cancelation is suppressed")
    ) >> IO.println(
      "This will never be called as we are canceled as soon as the uncancelable block finishes"
    )).start
  res <- fib.join
} yield res

run.unsafeRunSync()
```
There is no analogue for this kind of functionality with errors. Second, if you sequence an error with `raiseError`, it's always possible to use `attempt` or `handleError` to *handle* the error and resume normal execution. No such functionality is available for cancelation.

In other words, cancelation is effective; it cannot be undone. It can be suppressed, but once it is observed, it must be respected by the canceled fiber. This feature is exceptionally important for ensuring deterministic evaluation and avoiding deadlocks.

Self-cancelation is intended for use-cases such as [supervisor nets](https://erlang.org/doc/man/supervisor.html) and other complex constructs which require the ability to manipulate their own evaluation in this fashion. It isn't something that will be found often in application code.
