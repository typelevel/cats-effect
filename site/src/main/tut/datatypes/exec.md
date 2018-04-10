---
layout: docsplus
title:  "Exec"
number: 12
source: "shared/src/main/scala/cats/effect/Exec.scala"
scaladoc: "#cats.effect.Exec"
---

A data type for encoding synchronous side effects as pure values.
It can be seen as the synchronous non-error-handling counterpart to `IO`.
You might want to use this type for programs that never have an asynchronous boundary.

As `Exec` is incapable of error-handling, it is not possible to create an instance of `Sync` for `Exec`.
However, if we give it error-handling capabilites using `EitherT[Exec, E, A]` it is indeed possible to implement a `Sync` instance.

To construct a value of `Exec` you can use the following constructors:

### Simple Effects — Exec.apply

It's arguably the most frequently used constructor and is equivalent to
`IO.apply`, suspending computations that can be evaluated
immediately, on the current thread and call-stack:

```scala
def apply[A](body: => A): Exec[Either[Throwable, A]]
```

Unlike `IO`, which catches and suspends the error inside its own type,
`Exec` on the other hand is incapable of doing so and thus returns an `Either[Throwable, A]`.
For example, interoperating with Java code oftentimes throws an `IllegalArgumentException`,
when performing some side  effects and providing a wrong argument.
The `Exec.apply` function will make sure that this is completely safe.

### Unexceptional Effects - Exec.delayNoCatch

For some side effects, we can guarantee that they will never throw an Exception.
For these, we can use the `delayNoCatch` constructor.

```scala
def delayNoCatch[A](body: => A): Exec[A]
```

This should be used with caution as thrown Exceptions inside the body will not be caught and can lead to undefined behaviour.


### Pure values - Exec.pure & Exec.eval

We can lift pure values into `Exec`, yielding `Exec` values that are
"already evaluated", by using `Exec.pure`

```scala
def pure[A](a: A): Exec[A]
```

Note that the given parameter is passed by value, not by name.

To convert pure values that aren't evaluated eagerly, you can use the `Exec.eval` constructor:

```scala
def eval[A](a: Eval[A]): Exec[A]
```

### Running an Exec

To perform the side effects suspended inside a value of `Exec[A]`, you can use the `unsafeRun` function:

```tut:book
Exec.delayNoCatch(println("Hello World")).unsafeRun
```

Ideally, you should only use this function once at the very edge of your program.

### Interop with other effect types

To convert a value of `Exec[A]` to `IO[A]` simply use the `toIO` function:

```tut:book
Exec.delayNoCatch(println("Hello World")).toIO
```

We can also convert `Exec` values to any other `F` that implements the `Sync` type class with the `to` function.
For example, the `toIO` function described above is actually implemented in terms of `to`:

```scala
def toIO(ea: Exec[A]): IO[A] = to[IO]
```


### Type class instances

As explained above, `Exec` does not have a `Sync` instance, however you can still sequence effects using its `Monad` instance.
We do however provide a `Sync` instance for `EitherT[Exec, Throwable, A]`.
There are also `Monoid` and 'Semigroup` instances for `Exec[A]`, whenever `A` itself is a `Monoid` or `Semigroup` respectively.


