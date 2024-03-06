---
id: typeclasses
title: Overview
---

![](assets/hierarchy-impure.jpeg)

The typeclass hierarchy within Cats Effect defines what it means, fundamentally, to *be* a functional effect. This is very similar to how we have a set of rules which tell us what it means to be a number, or what it means to have a `flatMap` method. Broadly, the rules for functional effects can be broken down into the following categories:

- Resource safety and cancelation
- Parallel evaluation
- State sharing between parallel processes
- Interactions with time, including current time and `sleep`
- Safe capture of side-effects which return values
- Safe capture of side-effects which invoke a callback

These are listed roughly in order of increasing power. Parallelism cannot be safely defined without some notion of resource safety and cancelation. Interactions with time have no meaning if there is no notion of parallel evaluation, since it would be impossible to observe the passage of time. Capturing and controlling side-effects makes it possible to do *anything* simply by "cheating" and capturing something like `new Thread`, and thus it has the greatest power of all of these categories.

Beyond the above, certain capabilities are *assumed* by Cats Effect but defined elsewhere (or rather, defined within [Cats](https://typelevel.org/cats)). Non-exhaustively, those capabilities are as follows:

- Transforming the value inside an effect by `map`ping over it
- Putting a value *into* an effect
- Composing multiple effectful computations together sequentially, such that each is dependent on the previous
- Raising and handling errors

Taken together, all of these capabilities define what it means to be an effect. Just as you can rely on the properties of integers when you perform basic mental arithmetic (e.g. you can assume that `1 + 2 + 3` is the same as `1 + 5`), so too can you rely on these powerful and general properties of *effects* to hold when you write complex programs. This allows you to understand and refactor your code based on rules and abstractions, rather than having to think about every possible implementation detail and use-case. Additionally, it makes it possible for you and others to write very generic code which composes together making an absolute minimum of assumptions. This is the foundation of the Cats Effect ecosystem.
