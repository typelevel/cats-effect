# Cats Effect 3 Migration Guide

### Concurrent: continual

<!-- todo explain -->

```scala
def continual[A, B](fa: F[A])(f: Either[Throwable, A] => F[B]): F[B] = MonadCancel[F].uncancelable { poll =>
  poll(fa).attempt.flatMap(f)
}
```

### Dispatcher

todo - Gavin wrote about this

#### Implementing Async

Types that used to implement `Async` but not `Concurrent` from CE2 might not be able to implement anything more than `Sync` in CE3 -
this has an impact on users who have used e.g.
[doobie](https://github.com/tpolecat/doobie)'s `ConnectionIO`, `slick.dbio.DBIO` with
[slick-effect](https://github.com/kubukoz/slick-effect), or
[ciris](https://cir.is)'s `ConfigValue` in a polymorphic context with an `Async[F]` constraint.

Please refer to each library's appropriate documentation to see how to adjust your code to this change.
<!-- todo We might have direct links to the appropriate migration guides here later on -->

#### shifting

The `IO.shift` / `ContextShift[F].shift` methods are gone, and they don't have a fully compatible counterpart.

In CE2, `shift` would ensure the rest of the fiber would be scheduled on the `ExecutionContext` instance (or the `ContextShift` instance) provided in the parameter. This was used for two reasons:

- to switch back from a thread pool not managed by the effect system (e.g. a callback handler in a Java HTTP client)
- to reschedule the fiber on the given `ExecutionContext`, which would give other fibers a chance to run on that context's threads. This is called yielding to the scheduler.

There is no longer a need for the former (shifting back), because interop with callback-based libraries is done through methods in `Async`, which now **switch back to the appropriate thread pool automatically**.

The latter (yielding back to the scheduler) should now be done with `Spawn[F].cede`.

#### Clock changes

todo
<!-- why `create` and mapK are gone (because it's a typeclass now)  -->

#### Tracing

Currently, improved stack traces are not implemented. <!-- todo link to some PRs for it? -->
