# Contributing

There's always lots to do! This is an incredibly exciting project used by countless teams and companies around the world. Ask in the [Discord development channel](https://discord.gg/QNnHKHq5Ts) (make sure to select the **dev** role in the **role-selection** channel) if you are unsure where to begin, or check out our [issue tracker](https://github.com/typelevel/cats-effect/issues) and try your hand at something that looks interesting! Please note that all of the Cats Effect maintainers are, unfortunately, extremely busy most of the time, so don't get discouraged if you don't get a response right away! We love you and we want you to join us, we just may have our hair on fire inside a melting production server at the exact moment you asked.

Anything which is marked with [**good first issue**](https://github.com/typelevel/cats-effect/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) is something that the Cats Effect maintainers have evaluated and determined is likely to not require a significant amount of time or prior knowledge of the code in order to fix. If you want to take on one of these tasks, just leave a comment on the issue and we'll assign it to you! Additionally, whenever we mark issues with this label, we are committing to doing our best to be extra-responsive to questions and pull requests against the issue so as to best help you find your feet as a contributor.

## Tooling

Cats Effect is built with [sbt](https://github.com/sbt/sbt), and you should be able to jump right in by running `sbt test`. I will note, however, that `sbt +test` takes about two hours on my laptop, so you probably *shouldn't* start there...

You'll find a Nix flake at the root of the repository if you're one of Those People™ ;-)

Please make sure to run `sbt prePR` (and commit the results!) before opening a pull request. This will take care of running both scalafmt and scalafix, ensuring that the build doesn't just immediately fail to compile your work.

All PRs and branches are built using GitHub Actions, covering a large set of permutations of JVM versions, Scala versions, JavaScript environments, and operating systems. The test suite is extremely comprehensive, though note that a large number of test cases are randomly generated (using ScalaCheck), and this can sometimes result in very rare bugs being detected only intermittently via random failures in CI. Nondeterministic failures should be deemed even more serious than deterministic failures and must be fixed promptly.

## Process

Fairly standard pull request workflow. Please do *not* rebase commits that you've already pushed to a non-draft pull request unless explicitly requested. It's also very helpful if you toggle on the "Allow edits and access to secrets by maintainers" check box. There are no secrets, so you aren't leaking anything. What this does is allows maintainers to push last-minute trivial fixes to your PR (e.g. scalafmt) and ensure it can be merged with appropriate attribution.

We maintain multiple primary branches within the Cats Effect repository. The active branches are dependent on the most recent release. The following examples assume the most recent releases were 3.4.1 and 2.5.7 (in the 2.x and 3.x lineages, respective). For those most recent releases, the active branches will be as follows:

- `series/3.x` (default)
  + Any *binary compatible* changes on top of a 3.x version which are not source- or forward-compatible
- `series/3.4.x`
  + Any *source-* and *forward-compatible* changes on top of the latest major/minor version in the 3.x lineage
- `series/2.x`
  + Any *binary compatible* changes on top of a 2.x version which are not source- or forward-compatible
- `series/2.5.x`
  + Any *source-* and *forward-compatible* changes on top of the latest major/minor version in the 2.x lineage
- `series/1.x` (emergency fixes only)
  + Don't work on top of this branch
- `docs`
  + The shared website definition (including adopters list, main page, etc)
  
When in doubt, just target your work against `series/3.x`. If you're *very* certain that your changes will be source- *and* forward-compatible, it's safe to target the latest major/minor series (e.g. `series/3.4.x`). Note that forward-compatibility means that you cannot *add or remove* an API, while backward-compatibility means you cannot *remove* an API.

Most releases will be made from the major/minor series branch (e.g. `series/3.4.x`) unless we've determined that it's time to release the next full minor version. With each patch release, we will pull request the major/minor series branch back into the full major branch (e.g. `series/3.x`) to ensure that changes are incorporated. This process is another reason to avoid rebasing or cherry picking already-pull-requested work, since doing so can cause conflicts.

## Licensing

Cats Effect is licensed under the Apache Software License 2.0. Opening a pull request is to be considered affirmative consent to incorporate your changes into the project, granting an unrestricted license to Typelevel to distribute and derive new work from your changes, as per the contribution terms of ASL 2.0. You also affirm that you own the rights to the code you are contributing. All contributors retain the copyright to their own work.
