
### Before you begin: this isn't a "quick start" guide

<!-- todo: can we have a "sticky note" look for this? -->
<!-- todo: possibly move below summarya -->
This guide is meant for existing users of Cats Effect 2 who want to upgrade their applications
to the 3.x series of releases, starting with 3.0.0.

> If you haven't used Cats Effect before
> and want to give it a try, please follow the [quick start guide](dead-link) instead!

## Summary

Here is a general view of the steps you should take to migrate your application to Cats Effect 3:

<!-- todo add links -->
1. Make sure your dependencies have upgraded
2. Run the Scalafix migration (optional)
3. Upgrade dependencies and Cats Effect itself
4. Fix remaining compilation issues using [the lookup table of replacements](#notable-changes)
5. Test your application. <!-- todo wording -->

## Make sure your dependencies have upgraded

Before you make any changes to your build or your code, you should make sure all of your direct and transitive dependencies have made releases compatible with Cats Effect 3.

<!-- todo find optimal way to use sbt-dependency-graph (or something) for this, show specific instructions -->

To get some aid in pinpointing problematic dependencies, for [sbt][sbt] users we recommend using existing tooling like
[the dependency graph plugin included in `sbt` since 1.4.0][dependency-graph]. Using the `whatDependsOn` task, you will be able to quickly see the libraries that pull in the problematic version.

[We are keeping track of the efforts of library authors to publish compatible releases](https://github.com/typelevel/cats-effect/issues/1330) as soon as possible when 3.0.0 final is out.

## Run the Scalafix migration

Many parts of this migration can be automated by using the [Scalafix][scalafix] migration.

> Note: In case of projects using Scala Steward, the migration should automatically be applied when you receive the update.

If you want to trigger the migration manually, you can follow [the instructions here](https://github.com/fthomas/cats-effect/blob/series/3.x/scalafix/README.md).

<!-- todo: inline scalafix instructions here and have a link in their readme anyway? -->

## Upgrade dependencies and Cats Effect itself

At this point, if you've run the Scalafix migration, your code will not compile. However, you should hold off going through the list of errors and fixing the remaining issues yourself at this point.

Now is the time to update **every dependency using cats-effect** to a CE3-compatible version.

<!-- todo: use evictions -->
<!-- [`sbt`'s eviction mechanism][sbt-eviction] -->

## Fix remaining compilation issues

<!-- todo -->

## Test your application

<!-- todo -->

You might also want to consider [sbt-missinglink](https://github.com/scalacenter/sbt-missinglink) to verify your classpath works with your code.


[sbt]: https://scala-sbt.org
[sbt-eviction]: https://www.scala-sbt.org/1.x/docs/Library-Management.html#Eviction+warning
[dependency-graph]: https://www.scala-sbt.org/1.x/docs/sbt-1.4-Release-Notes.html#sbt-dependency-graph+is+in-sourced
[scalafix]: https://scalacenter.github.io/scalafix/
