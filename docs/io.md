---
id: io
title: IO
---


## Programs as data

One of very important principles in functional programming is the separation of programs into

- Description of what needs to be done
- Interpretation of that description

This "programs as data" concept allows us to obtain a value that represents the whole program.
We can then interpret it, which means translating it into something valuable. 
For example, a description of a REST API can be interpreted by a web server into a set of endpoints to be served, or by Swagger to generate API documentation.

## What's an IO?

Here's a simple definition: `IO` is a type that Cats Effect uses to capture the description of a program.
It's lazy, which means it won't be evaluated until it gets interpreted by the runtime.
Laziness allows for easy reasoning and refactoring and is crucial for the "programs as data" concept (which is why Scala `Future` cannot be used for the same purpose).

`IO` was initially conceived as a simple schoolbook example of a Cats Effect type for capturing effects.
It implemented the type classes, but contained only the basic primitives and as such wasn't intended to be used "in production"; other libraries at the time provided fully fledged effect types that were more powerful.
However, partially driven by the fact that users were utilizing `IO` more than anticipated, it has eventually grown into a fully fledged type for capturing synchronous and asynchronous effects, with powerful and highly performant concurrency capabilities.

## Running the IO

Once we have built our program as an `IO` value, we can evaluate it in two ways:

1. Using `unsafeRun*` methods 
   
2. Using `IOApp`.

First option runs the program here and now, and it should be done "at the end of the world" - the point in our program when we have finished building the description of what needs to be done, and are ready to evaluate the program and perform its side effects.
Note that once we breach that point, all guarantees on laziness / suspended side effects are off, which is exactly why we want to do it as late as possible, after we are done building our program.

Second option is the preferred one - extending the main entry point of our program with `IOApp` allows us to simply provide the final `IO` value (containing the full description of our program) and let the library worry about execution.
Note that we still have the option of controlling the details of that execution (e.g. on which thread pool to run)  using various methods on `IO` and its type class instances.

## Important operations

Here are some most important methods provided in `IO`:
  
TODO: Should we just describe them and refer the reader to appropriate, not-yet-existing `example` package with relevant code, or do we want the snippets here?

- `race`: TODO
  
- `both`: TODO
  
- `parTraverse`: TODO

- `start`: TODO

- `join`: TODO

- `ref`: TODO

- `deferred`: TODO


## IO runs on fibers

We build our program description by using various `IO` constructs, most importantly flatmapping different `IO` values into a chain of computations.
Here is an example snippet (remember that for-comprehension is syntactic sugar for a chain of `flatMap`s followed by a `map`):

```scala mdoc
def getUserFromDatabase(): IO[User] = ???
def sendHttpRequest(user: User): IO[Response] = ???
def logResponse(response: Response): IO[Unit] = ???

val ourProgram = for {
  user <- getUserFromDatabase
  response <- sendHttpRequest(user)
  _ <- logResponse(response)
} yield response
```

Every fiber consists of a sequence of such flatmapped `IO` values.
More information on fibers can be found in the [tutorial](tutorial.md), but in a nutshell, that's what a fiber really is. The continuation (sequence of flatmapped `IO` values) is then later assigned by the scheduler to run on one of the available threads. 
Don't forget, the `IO` itself merely holds a description of the program - no database calls have yet been made, no HTTP requests sent, nothing.
Once we interpret it, the runtime will materialize it into a fiber, ready to be scheduled for running on a thread.

As a quick side note, a fiber can volunteer to be taken off its thread, thus achieving cooperative yielding by allowing one of the other waiting fibers to take the thread. 
This "volunteering" is by default performed every 1024 flatmap steps, and we can do it manually via `IO.cede`. 
Having many more fibers than we have threads is thus OK, because a) fibers reside fully in memory, don't block any system resources and have very little context switching overhead, and b) starvation is prevented by cooperative yielding.
This is also the reason why it's OK to "block a fiber" - it's only semantically blocking, because all that's really happening is that the fiber gets taken off its thread. The thread itself is not blocked, and it will happily run another fiber.

Now, if we wanted to run the database query on one fiber, send the request on another, and use a third one for logging, all we'd have to do is use the appropriate combinators (see the [Important operations](#important-operations) section).
We can run them sequentially, in parallel, wait with one until others have finished, and so on.
It should also be said that most libraries will take care of this micro-management for you - for example, http4s will by default serve each request using a separate fiber.
But it's important to know that this can be done manually as well.

## Conclusion

Today's `IO`  went a long way from being a "schoolbook example effect type". 
Cats Effect 3 invested a lot of time and effort into making its fiber scheduling and running mechanisms very performant, and the API rich and flexible, turning `IO` into one of the most powerful effect types in Scala. 