# Typeclasses

The typeclass hierarchy within Cats Effect defines what it means, fundamentally, to *be* a functional effect. This is very similar to how we have a set of rules which tell us what it means to be a number, or what it means to have a `flatMap` method. Broadly, the rules for functional effects can be broken down into the following categories:

- Resource safety and preemption
- Parallel evaluation
- State sharing between parallel processes
- Interactions with time, including current time and `sleep`
- Safe capture of side-effects which return values
- Safe capture of side-effects which invoke a callback

These are listed roughly in order of increasing power. Parallelism cannot be safely defined without some notion of resource safety and preemption. Interactions with time have no meaning if there is no notion of parallel evaluation, since it would be impossible to observe the passage of time. Capturing and controlling side-effects makes it possible to do *anything* simply by "cheating" and capturing something like `new Thread`, and thus it has the greatest power of all of these categories.

Beyond the above, certain capabilities are *assumed* by Cats Effect but defined elsewhere (or rather, defined within [Cats](https://typelevel.org/cats)). Non-exhaustively, those capabilities are as follows:

- Transforming the value inside an effect by `map`ping over it
- Putting a value *into* an effect
- Composing multiple effectful computations together sequentially, such that each is dependent on the previous
- Raising and handling errors

Taken together, all of these capabilities define what it means to be an effect. Just as you can rely on the properties of integers when you perform basic mental arithmetic (e.g. you can assume that `1 + 2 + 3` is the same as `1 + 5`), so too can you rely on these powerful and general properties of *effects* to hold when you write complex programs. This allows you to understand and refactor your code based on rules and abstractions, rather than having to think about every possibly implementation detail and use-case. Additionally, it makes it possible for you and others to write very generic code which composes together making an absolute minimum of assumptions. This is the foundation of the Cats Effect ecosystem.

## `MonadCancel`

This typeclass extends `MonadError` (in Cats) and defines the meaning of resource safety and preemption (cancelation). Using it, we can define effects which safely acquire and release resources:

<!-- TODO enable mdoc for this -->
```scala
import cats.effect._
import cats.effect.syntax.all._

openFile.bracket(fis => readFromFile(fis))(fis => closeFile(fis))
```

The `bracket` combinator works a bit like the FP equivalent of `try`/`finally`: if `openFile` runs (in the above), then `closeFile` *will* be run, no matter what. This will happen even if `readFromFile` produces an error, or even if the whole process is canceled by some other fiber. Additionally, `openFile` itself is atomic: it either doesn't evaluate at all (e.g. if the current fiber is canceled prior to any of this even happening), or it *fully* evaluates. This allows `openFile` to do complicated things in the process of acquiring the resource without fear of something external getting in the way.

In addition to `bracket`, `MonadCancel` also provides a lower-level operation, `uncancelable`, which makes it possible to perform extremely complex, cancelation-sensitive actions in a safe and composable manner. For example, imagine that we have a block of code which must be guarded by a `Semaphore`, ensuring the runtime has exclusive access when evaluating. The problem here is that the acquisition of the `Semaphore`, which is a resource, may *also* result in blocking the fiber, and thus may need to be canceled externally. Put another way: resource acquisition needs to be uncancelable, but this *particular* resource acquisition has a very specific point at which it needs to allow cancelation, otherwise it might end up locking up the JVM. `uncancelable` provides a mechanism to achieve this:

```scala mdoc
import cats.effect.MonadCancel
import cats.effect.concurrent.Semaphore
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

      a <- poll(use(r)).onCancel(releaseAll).onError(_ => releaseAll)
      _ <- releaseAll.attempt
    } yield a
  }
```

The above looks intimidating, but it's actually just another flavor of `bracket`, the operation we saw earlier! The whole thing is wrapped in `uncancelable`, which means we don't need to worry about other fibers interrupting us in the middle of each of these actions. In particular, the very *first* action we perform is `alloc`, which allocates a value of type `R`. Once this has completed successfully, we attempt to acquire the `Semaphore`. If some other fiber has already acquired the lock, this may end up blocking for quite a while. We *want* other fibers to be able to interrupt this blocking, so we wrap the acquisition in `poll`.

You can think of `poll` like a "cancelable" block: it re-enables cancelation for whatever is inside of it, but *only* if that's possible. There's a reason it's not just called `cancelable`! We'll come back to this a little bit later.

If the semaphore acquisition is canceled, we want to make sure we still release the resource, `r`, so we use `onCancel` to achieve this. From that moment forward, if and when we're ready to release our resource, we want to *also* release the semaphore, so we create an effect which merges these two things together.

Finally we move on to invoking the `use(r)` action, which again we need to wrap in `poll` to ensure that it can be interrupted by an external fiber. This `use(r)` action may take quite a long time and have a lot of complicated internal workings, and the last thing we want to do is suppress cancelation for its entire duration. It's likely safe to cancel `use`, because the resource management is already handled (by us!).

Finally, once `use(r)` is done, we run `releaseAll`. The use of `onCancel` and `onError` are to ensure that `releaseAll` is always run, regardless of whether `use(r)` is canceled, raises an error, or completes normally.

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

The `inner` poll eliminates the inner `uncancelable`, but the *outer* `uncancelable` still applies, meaning that this whole thing is equivalent to `MonadCancel[F].uncancelable(_ => fa)`.

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

The use of the `outer` poll within the inner `uncancelable` is simply ignored.

### Self-Cancelation

One particularly unique aspect of `MonadCancel` is the ability to self-cancel. For example:

```scala
MonadCancel[F].canceled >> fa
```

The above will result in a canceled evaluation, and `fa` will never be run, *provided* that it isn't wrapped in an `uncancelable` block. If the above is wrapped in `uncancelable`, then the `canceled` effect will be ignored.

Self-cancelation is somewhat similar to raising an error with `raiseError` in that it will short-circuit evaluation and begin "popping" back up the stack until it hits a handler. Just as `raiseError` can be observed using the `onError` method, `canceled` can be observed using `onCancel`.

The primary differences between self-cancelation and `raiseError` are two-fold. First, `uncancelable` suppresses `canceled` within its body (unless `poll`ed!), turning it into something equivalent to just `().pure[F]`. There is no analogue for this kind of functionality with errors. Second, if you sequence an error with `raiseError`, it's always possible to use `attempt` or `handleError` to *handle* the error and resume normal execution. No such functionality is available for cancelation.

In other words, cancelation is *final*. It can be observed, but it cannot be *prevented*. This feature is exceptionally important for ensuring deterministic evaluation and avoiding deadlocks.

Self-cancelation is intended for use-cases such as [supervisor nets](https://erlang.org/doc/man/supervisor.html) and other complex constructs which require the ability to manipulate their own evaluation in this fashion. It isn't something that will be found often in application code.

