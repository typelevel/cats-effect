// package cats.effect.std

// import cats.{Id, catsInstancesForId}
// import org.scalatest.flatspec.AnyFlatSpec
// import retry.syntax.all._

// import scala.collection.mutable.ArrayBuffer
// import scala.concurrent.duration._

// class SyntaxSpec extends AnyFlatSpec {
//   type StringOr[A] = Either[String, A]

//   behavior of "retryingOnFailures"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy: RetryPolicy[Id] =
//       RetryPolicies.constantDelay[Id](1.second)
//     def onFailure: (String, RetryDetails) => Id[Unit] = onError
//     def wasSuccessful(res: String): Id[Boolean]       = res.toInt > 3
//     val sleeps                                        = ArrayBuffer.empty[FiniteDuration]

//     implicit val dummySleep: Sleep[Id] =
//       (delay: FiniteDuration) => sleeps.append(delay)

//     def action: Id[String] = {
//       attempts = attempts + 1
//       attempts.toString
//     }

//     val finalResult: Id[String] =
//       action.retryingOnFailures(wasSuccessful, policy, onFailure)

//     assert(finalResult == "4")
//     assert(attempts == 4)
//     assert(errors.toList == List("1", "2", "3"))
//     assert(delays.toList == List(1.second, 1.second, 1.second))
//     assert(sleeps.toList == delays.toList)
//     assert(!gaveUp)
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     val policy: RetryPolicy[Id]        = RetryPolicies.limitRetries[Id](2)
//     implicit val dummySleep: Sleep[Id] = _ => ()

//     def action: Id[String] = {
//       attempts = attempts + 1
//       attempts.toString
//     }

//     val finalResult: Id[String] =
//       action.retryingOnFailures(_.toInt > 3, policy, onError)

//     assert(finalResult == "3")
//     assert(attempts == 3)
//     assert(errors.toList == List("1", "2", "3"))
//     assert(delays.toList == List(Duration.Zero, Duration.Zero))
//     assert(gaveUp)
//   }

//   behavior of "retryingOnSomeErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//     val policy: RetryPolicy[StringOr] =
//       RetryPolicies.constantDelay[StringOr](1.second)

//     def action: StringOr[String] = {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     val finalResult: StringOr[String] =
//       action.retryingOnSomeErrors(
//         s => Right(s == "one more time"),
//         policy,
//         (err, rd) => onError(err, rd)
//       )

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry only if the error is worth retrying" in new TestContext {
//     implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//     val policy: RetryPolicy[StringOr] =
//       RetryPolicies.constantDelay[StringOr](1.second)

//     def action: StringOr[Nothing] = {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Left("nope")
//     }

//     val finalResult =
//       action.retryingOnSomeErrors(
//         s => Right(s == "one more time"),
//         policy,
//         (err, rd) => onError(err, rd)
//       )

//     assert(finalResult == Left("nope"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(
//       !gaveUp
//     ) // false because onError is only called when the error is worth retrying
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//     val policy: RetryPolicy[StringOr] =
//       RetryPolicies.limitRetries[StringOr](2)

//     def action: StringOr[Nothing] = {
//       attempts = attempts + 1

//       Left("one more time")
//     }

//     val finalResult: StringOr[Nothing] =
//       action.retryingOnSomeErrors(
//         s => Right(s == "one more time"),
//         policy,
//         (err, rd) => onError(err, rd)
//       )

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   behavior of "retryingOnAllErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//     val policy: RetryPolicy[StringOr] =
//       RetryPolicies.constantDelay[StringOr](1.second)

//     def action: StringOr[String] = {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     val finalResult: StringOr[String] =
//       action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//     val policy: RetryPolicy[StringOr] =
//       RetryPolicies.limitRetries[StringOr](2)

//     def action: StringOr[Nothing] = {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     val finalResult =
//       action.retryingOnAllErrors(policy, (err, rd) => onError(err, rd))

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   private class TestContext {
//     var attempts = 0
//     val errors   = ArrayBuffer.empty[String]
//     val delays   = ArrayBuffer.empty[FiniteDuration]
//     var gaveUp   = false

//     def onError(error: String, details: RetryDetails): Either[String, Unit] = {
//       errors.append(error)
//       details match {
//         case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
//         case RetryDetails.GivingUp(_, _)                 => gaveUp = true
//       }
//       Right(())
//     }
//   }
// }

// import org.scalatest.flatspec.AnyFlatSpec

// class FibonacciSpec extends AnyFlatSpec {
//   it should "calculate the Fibonacci sequence" in {
//     assert(Fibonacci.fibonacci(0) == 0)
//     assert(Fibonacci.fibonacci(1) == 1)
//     assert(Fibonacci.fibonacci(2) == 1)
//     assert(Fibonacci.fibonacci(3) == 2)
//     assert(Fibonacci.fibonacci(4) == 3)
//     assert(Fibonacci.fibonacci(5) == 5)
//     assert(Fibonacci.fibonacci(6) == 8)
//     assert(Fibonacci.fibonacci(7) == 13)
//     assert(Fibonacci.fibonacci(75) == 2111485077978050L)
//   }
// }

// import cats.{Id, catsInstancesForId}
// import org.scalatest.flatspec.AnyFlatSpec

// import scala.collection.mutable.ArrayBuffer
// import scala.concurrent.duration._

// class PackageObjectSpec extends AnyFlatSpec {
//   type StringOr[A] = Either[String, A]

//   implicit val sleepForEither: Sleep[StringOr] = _ => Right(())

//   behavior of "retryingOnFailures"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy = RetryPolicies.constantDelay[Id](1.second)

//     val sleeps = ArrayBuffer.empty[FiniteDuration]

//     implicit val dummySleep: Sleep[Id] =
//       (delay: FiniteDuration) => sleeps.append(delay)

//     val finalResult = retryingOnFailures[String][Id](
//       policy,
//       _.toInt > 3,
//       onError
//     ) {
//       attempts = attempts + 1
//       attempts.toString
//     }

//     assert(finalResult == "4")
//     assert(attempts == 4)
//     assert(errors.toList == List("1", "2", "3"))
//     assert(delays.toList == List(1.second, 1.second, 1.second))
//     assert(sleeps.toList == delays.toList)
//     assert(!gaveUp)
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     val policy = RetryPolicies.limitRetries[Id](2)

//     implicit val dummySleep: Sleep[Id] = _ => ()

//     val finalResult = retryingOnFailures[String][Id](
//       policy,
//       _.toInt > 3,
//       onError
//     ) {
//       attempts = attempts + 1
//       attempts.toString
//     }

//     assert(finalResult == "3")
//     assert(attempts == 3)
//     assert(errors.toList == List("1", "2", "3"))
//     assert(delays.toList == List(Duration.Zero, Duration.Zero))
//     assert(gaveUp)
//   }

//   it should "retry in a stack-safe way" in new TestContext {
//     val policy = RetryPolicies.limitRetries[Id](10000)

//     implicit val dummySleep: Sleep[Id] = _ => ()

//     val finalResult = retryingOnFailures[String][Id](
//       policy,
//       _.toInt > 20000,
//       onError
//     ) {
//       attempts = attempts + 1
//       attempts.toString
//     }

//     assert(finalResult == "10001")
//     assert(attempts == 10001)
//     assert(gaveUp)
//   }

//   behavior of "retryingOnSomeErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnSomeErrors(
//       policy,
//       (s: String) => Right(s == "one more time"),
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry only if the error is worth retrying" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnSomeErrors(
//       policy,
//       (s: String) => Right(s == "one more time"),
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Left("nope")
//     }

//     assert(finalResult == Left("nope"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(
//       !gaveUp
//     ) // false because onError is only called when the error is worth retrying
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnSomeErrors(
//       policy,
//       (s: String) => Right(s == "one more time"),
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   it should "retry in a stack-safe way" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnSomeErrors(
//       policy,
//       (s: String) => Right(s == "one more time"),
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 10001)
//     assert(gaveUp)
//   }

//   behavior of "retryingOnAllErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnAllErrors(
//       policy,
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry until the policy chooses to give up" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnAllErrors(
//       policy,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   it should "retry in a stack-safe way" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnAllErrors(
//       policy,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 10001)
//     assert(gaveUp)
//   }

//   behavior of "retryingOnFailuresAndSomeErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "yay"),
//       (s: String) => Right(s == "one more time"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry only if the error is worth retrying" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "will never happen"),
//       (s: String) => Right(s == "one more time"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Left("nope")
//     }

//     assert(finalResult == Left("nope"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(
//       !gaveUp
//     ) // false because onError is only called when the error is worth retrying
//   }

//   it should "retry until the policy chooses to give up due to errors" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "will never happen"),
//       (s: String) => Right(s == "one more time"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   it should "retry until the policy chooses to give up due to failures" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "yay"),
//       (s: String) => Right(s == "one more time"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Right("boo")
//     }

//     assert(finalResult == Right("boo"))
//     assert(attempts == 3)
//     assert(errors.toList == List("boo", "boo", "boo"))
//     assert(gaveUp)
//   }

//   it should "retry in a stack-safe way" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "yay"),
//       (s: String) => Right(s == "one more time"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 10001)
//     assert(gaveUp)
//   }

//   it should "should fail fast if isWorthRetrying's effect fails" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnFailuresAndSomeErrors[String](
//       policy,
//       s => Right(s == "yay, but it doesn't matter"),
//       (_: String) => Left("isWorthRetrying failed"): StringOr[Boolean],
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("isWorthRetrying failed"))
//     assert(attempts == 1)
//     assert(!gaveUp)
//   }

//   behavior of "retryingOnFailuresAndAllErrors"

//   it should "retry until the action succeeds" in new TestContext {
//     val policy = RetryPolicies.constantDelay[StringOr](1.second)

//     val finalResult = retryingOnFailuresAndAllErrors[String](
//       policy,
//       s => Right(s == "yay"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       if (attempts < 3)
//         Left("one more time")
//       else
//         Right("yay")
//     }

//     assert(finalResult == Right("yay"))
//     assert(attempts == 3)
//     assert(errors.toList == List("one more time", "one more time"))
//     assert(!gaveUp)
//   }

//   it should "retry until the policy chooses to give up due to errors" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnFailuresAndAllErrors[String](
//       policy,
//       s => Right(s == "will never happen"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 3)
//     assert(
//       errors.toList == List("one more time", "one more time", "one more time")
//     )
//     assert(gaveUp)
//   }

//   it should "retry until the policy chooses to give up due to failures" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](2)

//     val finalResult = retryingOnFailuresAndAllErrors[String](
//       policy,
//       s => Right(s == "yay"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Right("boo")
//     }

//     assert(finalResult == Right("boo"))
//     assert(attempts == 3)
//     assert(errors.toList == List("boo", "boo", "boo"))
//     assert(gaveUp)
//   }

//   it should "retry in a stack-safe way" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnFailuresAndAllErrors[String](
//       policy,
//       s => Right(s == "will never happen"),
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Left("one more time")
//     }

//     assert(finalResult == Left("one more time"))
//     assert(attempts == 10001)
//     assert(gaveUp)
//   }

//   it should "should fail fast if wasSuccessful's effect fails" in new TestContext {
//     val policy = RetryPolicies.limitRetries[StringOr](10000)

//     val finalResult = retryingOnFailuresAndAllErrors[String](
//       policy,
//       _ => Left("an error was raised!"): StringOr[Boolean],
//       onError,
//       onError
//     ) {
//       attempts = attempts + 1
//       Right("one more time")
//     }

//     assert(finalResult == Left("an error was raised!"))
//     assert(attempts == 1)
//     assert(!gaveUp)
//   }

//   private class TestContext {
//     var attempts = 0
//     val errors   = ArrayBuffer.empty[String]
//     val delays   = ArrayBuffer.empty[FiniteDuration]
//     var gaveUp   = false

//     def onError(error: String, details: RetryDetails): Either[String, Unit] = {
//       errors.append(error)
//       details match {
//         case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
//         case RetryDetails.GivingUp(_, _)                 => gaveUp = true
//       }
//       Right(())
//     }
//   }
// }

// import java.util.concurrent.TimeUnit

// import retry.RetryPolicies._
// import cats.{Id, catsInstancesForId}
// import org.scalacheck.{Arbitrary, Gen}
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatestplus.scalacheck.Checkers
// import retry.PolicyDecision.{DelayAndRetry, GiveUp}

// import scala.concurrent.duration._

// class RetryPoliciesSpec extends AnyFlatSpec with Checkers {
//   override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
//     PropertyCheckConfiguration(minSuccessful = 100)

//   implicit val arbRetryStatus: Arbitrary[RetryStatus] = Arbitrary {
//     for {
//       a <- Gen.choose(0, 1000)
//       b <- Gen.choose(0, 1000)
//       c <- Gen.option(Gen.choose(b, 10000))
//     } yield RetryStatus(
//       a,
//       FiniteDuration(b, TimeUnit.MILLISECONDS),
//       c.map(FiniteDuration(_, TimeUnit.MILLISECONDS))
//     )
//   }

//   val genFiniteDuration: Gen[FiniteDuration] =
//     Gen.posNum[Long].map(FiniteDuration(_, TimeUnit.NANOSECONDS))

//   case class LabelledRetryPolicy(policy: RetryPolicy[Id], description: String) {
//     override def toString: String = description
//   }

//   implicit val arbRetryPolicy: Arbitrary[LabelledRetryPolicy] = Arbitrary {
//     Gen.oneOf(
//       Gen.const(LabelledRetryPolicy(alwaysGiveUp[Id], "alwaysGiveUp")),
//       genFiniteDuration.map(delay =>
//         LabelledRetryPolicy(
//           constantDelay[Id](delay),
//           s"constantDelay($delay)"
//         )
//       ),
//       genFiniteDuration.map(baseDelay =>
//         LabelledRetryPolicy(
//           exponentialBackoff[Id](baseDelay),
//           s"exponentialBackoff($baseDelay)"
//         )
//       ),
//       Gen
//         .posNum[Int]
//         .map(maxRetries =>
//           LabelledRetryPolicy(
//             limitRetries(maxRetries),
//             s"limitRetries($maxRetries)"
//           )
//         ),
//       genFiniteDuration.map(baseDelay =>
//         LabelledRetryPolicy(
//           fibonacciBackoff[Id](baseDelay),
//           s"fibonacciBackoff($baseDelay)"
//         )
//       ),
//       genFiniteDuration.map(baseDelay =>
//         LabelledRetryPolicy(
//           fullJitter[Id](baseDelay),
//           s"fullJitter($baseDelay)"
//         )
//       )
//     )
//   }

//   behavior of "constantDelay"

//   it should "always retry with the same delay" in check {
//     (status: RetryStatus) =>
//       constantDelay[Id](1.second)
//         .decideNextRetry(status) == PolicyDecision.DelayAndRetry(1.second)
//   }

//   behavior of "exponentialBackoff"

//   it should "start with the base delay and double the delay after each iteration" in {
//     val policy                   = exponentialBackoff[Id](100.milliseconds)
//     val arbitraryCumulativeDelay = 999.milliseconds
//     val arbitraryPreviousDelay   = Some(999.milliseconds)

//     def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
//       val status = RetryStatus(
//         retriesSoFar,
//         arbitraryCumulativeDelay,
//         arbitraryPreviousDelay
//       )
//       val verdict = policy.decideNextRetry(status)
//       assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
//     }

//     test(0, 100.milliseconds)
//     test(1, 200.milliseconds)
//     test(2, 400.milliseconds)
//     test(3, 800.milliseconds)
//   }

//   behavior of "fibonacciBackoff"

//   it should "start with the base delay and increase the delay in a Fibonacci-y way" in {
//     val policy                   = fibonacciBackoff[Id](100.milliseconds)
//     val arbitraryCumulativeDelay = 999.milliseconds
//     val arbitraryPreviousDelay   = Some(999.milliseconds)

//     def test(retriesSoFar: Int, expectedDelay: FiniteDuration) = {
//       val status = RetryStatus(
//         retriesSoFar,
//         arbitraryCumulativeDelay,
//         arbitraryPreviousDelay
//       )
//       val verdict = policy.decideNextRetry(status)
//       assert(verdict == PolicyDecision.DelayAndRetry(expectedDelay))
//     }

//     test(0, 100.milliseconds)
//     test(1, 100.milliseconds)
//     test(2, 200.milliseconds)
//     test(3, 300.milliseconds)
//     test(4, 500.milliseconds)
//     test(5, 800.milliseconds)
//     test(6, 1300.milliseconds)
//     test(7, 2100.milliseconds)
//   }

//   behavior of "fullJitter"

//   it should "implement the AWS Full Jitter backoff algorithm" in {
//     val policy                   = fullJitter[Id](100.milliseconds)
//     val arbitraryCumulativeDelay = 999.milliseconds
//     val arbitraryPreviousDelay   = Some(999.milliseconds)

//     def test(retriesSoFar: Int, expectedMaximumDelay: FiniteDuration): Unit = {
//       val status = RetryStatus(
//         retriesSoFar,
//         arbitraryCumulativeDelay,
//         arbitraryPreviousDelay
//       )
//       for (_ <- 1 to 1000) {
//         val verdict = policy.decideNextRetry(status)
//         val delay   = verdict.asInstanceOf[PolicyDecision.DelayAndRetry].delay
//         assert(delay >= Duration.Zero)
//         assert(delay < expectedMaximumDelay)
//       }
//     }

//     test(0, 100.milliseconds)
//     test(1, 200.milliseconds)
//     test(2, 400.milliseconds)
//     test(3, 800.milliseconds)
//     test(4, 1600.milliseconds)
//     test(5, 3200.milliseconds)
//   }

//   behavior of "all built-in policies"

//   it should "never try to create a FiniteDuration of more than Long.MaxValue nanoseconds" in check {
//     (labelledPolicy: LabelledRetryPolicy, status: RetryStatus) =>
//       labelledPolicy.policy.decideNextRetry(status) match {
//         case PolicyDecision.DelayAndRetry(nextDelay) =>
//           nextDelay.toNanos <= Long.MaxValue
//         case PolicyDecision.GiveUp => true
//       }
//   }

//   behavior of "limitRetries"

//   it should "retry with no delay until the limit is reached" in check {
//     (status: RetryStatus) =>
//       val limit = 500
//       val verdict =
//         limitRetries[Id](limit).decideNextRetry(status)
//       if (status.retriesSoFar < limit) {
//         verdict == PolicyDecision.DelayAndRetry(Duration.Zero)
//       } else {
//         verdict == PolicyDecision.GiveUp
//       }
//   }

//   behavior of "capDelay"

//   it should "cap the delay" in {
//     check { (status: RetryStatus) =>
//       capDelay(100.milliseconds, constantDelay[Id](101.milliseconds))
//         .decideNextRetry(status) == DelayAndRetry(100.milliseconds)
//     }

//     check { (status: RetryStatus) =>
//       capDelay(100.milliseconds, constantDelay[Id](99.milliseconds))
//         .decideNextRetry(status) == DelayAndRetry(99.milliseconds)
//     }
//   }

//   behavior of "limitRetriesByDelay"

//   it should "give up if the underlying policy chooses a delay greater than the threshold" in {
//     check { (status: RetryStatus) =>
//       limitRetriesByDelay(100.milliseconds, constantDelay[Id](101.milliseconds))
//         .decideNextRetry(status) == GiveUp
//     }

//     check { (status: RetryStatus) =>
//       limitRetriesByDelay(100.milliseconds, constantDelay[Id](99.milliseconds))
//         .decideNextRetry(status) == DelayAndRetry(99.milliseconds)
//     }
//   }

//   behavior of "limitRetriesByCumulativeDelay"

//   it should "give up if cumulativeDelay + underlying policy's next delay >= threshold" in {
//     val cumulativeDelay        = 400.milliseconds
//     val arbitraryRetriesSoFar  = 5
//     val arbitraryPreviousDelay = Some(123.milliseconds)
//     val status = RetryStatus(
//       arbitraryRetriesSoFar,
//       cumulativeDelay,
//       arbitraryPreviousDelay
//     )

//     val threshold = 500.milliseconds

//     def test(
//         underlyingPolicy: RetryPolicy[Id],
//         expectedDecision: PolicyDecision
//     ) = {
//       val policy = limitRetriesByCumulativeDelay(threshold, underlyingPolicy)
//       assert(policy.decideNextRetry(status) == expectedDecision)
//     }

//     test(constantDelay(98.milliseconds), DelayAndRetry(98.milliseconds))
//     test(constantDelay(99.milliseconds), DelayAndRetry(99.milliseconds))
//     test(constantDelay(100.milliseconds), GiveUp)
//     test(constantDelay(101.milliseconds), GiveUp)
//   }
// }

// import cats.{Id, catsInstancesForId}
// import cats.syntax.semigroup._
// import org.scalatest.flatspec.AnyFlatSpec

// import scala.concurrent.duration._

// class RetryPolicySpec extends AnyFlatSpec {
//   behavior of "BoundedSemilattice append"

//   it should "give up if either of the composed policies decides to give up" in {
//     val alwaysGiveUp = RetryPolicy.lift[Id](_ => PolicyDecision.GiveUp)
//     val alwaysRetry  = RetryPolicies.constantDelay[Id](1.second)

//     assert(
//       (alwaysGiveUp |+| alwaysRetry)
//         .decideNextRetry(RetryStatus.NoRetriesYet) == PolicyDecision.GiveUp
//     )
//     assert(
//       (alwaysRetry |+| alwaysGiveUp)
//         .decideNextRetry(RetryStatus.NoRetriesYet) == PolicyDecision.GiveUp
//     )
//   }

//   it should "choose the maximum of the delays if both of the composed policies decides to retry" in {
//     val delayOneSecond =
//       RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(1.second))
//     val delayTwoSeconds =
//       RetryPolicy.lift[Id](_ => PolicyDecision.DelayAndRetry(2.seconds))

//     assert(
//       (delayOneSecond |+| delayTwoSeconds).decideNextRetry(
//         RetryStatus.NoRetriesYet
//       ) == PolicyDecision.DelayAndRetry(2.seconds)
//     )
//     assert(
//       (delayTwoSeconds |+| delayOneSecond).decideNextRetry(
//         RetryStatus.NoRetriesYet
//       ) == PolicyDecision.DelayAndRetry(2.seconds)
//     )
//   }
// }
