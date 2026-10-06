---
id: stack
title: Stack
---

A `Stack` is a concurrent data structure which allows insertion and retrieval of
elements of in a last-in-first-out (LIFO) manner.

```scala
trait  Stack[F[_], A] {
  def push(a: A): F[Unit]

  def pushN(as: A*): F[Unit]

  def pop: F[A]

  def tryPop: F[Option[A]]

  def peek: F[Option[A]]
}
```

* `push`: Pushes an element to the top of the `Stack`, never blocks and will always succeed.
* `pushN`: Pushes many element sto the top of the `Stack`, the last element will be the final top, never blocks and will always succeed.
* `pop`: Retrieves the top element from the `Stack`, semantically blocks when the `Stack` is empty.
* `tryPop`: Similar to `pop` but rather than blocking, when empty will return `None`.
* `peek` Similar to `tryPop` but would not remove the element from the `Stack`. There is no guarantee that a consequent `pop`, `tryPop`, or `peek` would return the same element due to concurrency.
