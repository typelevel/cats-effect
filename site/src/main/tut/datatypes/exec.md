---
layout: docsplus
title:  "UExec"
number: 12
source: "shared/src/main/scala/cats/effect/UExec.scala"
scaladoc: "#cats.effect.UExec"
---

A data type for encoding synchronous side effects as pure values.
It can be seen as the synchronous non-error-handling counterpart to `IO`.
You might want to use this type for programs that never have an asynchronous boundary.

As `UExec` is incapable of error-handling, it is not possible to create an instance of `Sync` for `UExec`.
However, if we give it error-handling capabilites using `EitherT[UExec, E, A]` it is indeed possible to implement a `Sync` instance.

To construct a value of `UExec` you can use the following constructors:

### Simple Effects — UExec.apply

It's arguably the most frequently used constructor and is equivalent to
`IO.apply`, suspending computations that can be evaluated
immediately, on the current thread and call-stack:

```scala
def apply[A](body: => A): UExec[Either[Throwable, A]]
```

Unlike `IO`, which catches and suspends the error inside its own type,
`UExec` on the other hand is incapable of doing so and thus returns an `Either[Throwable, A]`.
For example, interoperating with Java code oftentimes throws an `IllegalArgumentException`,
when performing some side  effects and providing a wrong argument.
The `UExec.apply` function will make sure that this is completely safe.

### Unexceptional Effects - UExec.delayNoCatch

For some side effects, we can guarantee that they will never throw an Exception.
For these, we can use the `delayNoCatch` constructor.

```scala
def delayNoCatch[A](body: => A): UExec[A]
```

This should be used with caution as thrown Exceptions inside the body will not be caught and can lead to undefined behaviour.


### Pure values - UExec.pure & UExec.eval

We can lift pure values into `UExec`, yielding `UExec` values that are
"already evaluated", by using `UExec.pure`

```scala
def pure[A](a: A): UExec[A]
```

Note that the given parameter is passed by value, not by name.

To convert pure values that aren't evaluated eagerly, you can use the `UExec.eval` constructor:

```scala
def eval[A](a: Eval[A]): UExec[A]
```

### Running an UExec

To perform the side effects suspended inside a value of `UExec[A]`, you can use the `unsafeRun` function:

```tut:book
UExec.delayNoCatch(println("Hello World")).unsafeRun
```

Ideally, you should only use this function once at the very edge of your program.

### Interop with other effect types

To convert a value of `UExec[A]` to `IO[A]` simply use the `toIO` function:

```tut:book
UExec.delayNoCatch(println("Hello World")).toIO
```

We can also convert `UExec` values to any other `F` that implements the `Sync` type class with the `to` function.
For example, the `toIO` function described above is actually implemented in terms of `to`:

```scala
def toIO(ea: UExec[A]): IO[A] = to[IO]
```


### Type class instances

As explained above, `UExec` does not have a `Sync` instance, however you can still sequence effects using its `Monad` instance.
We do however provide a `Sync` instance for `EitherT[UExec, Throwable, A]`.
There are also `Monoid` and 'Semigroup` instances for `UExec[A]`, whenever `A` itself is a `Monoid` or `Semigroup` respectively.


