# Release procedure

1. Draft a new release.
1. Create a new tag with the appropriate name (e.g. v3.5.1); also name the release accordingly.
1. Make sure you're targeting the appropriate branch! (probably the minor branch)
1. "Generate new release notes"
1. edit edit edit
   - Increment and transliterate the release counter
   - Cargo-cult the old release notes header from the last minor release (including any warnings)
   - Add any special notes to the release header (any major changes or fixes that need calling out)
   - Fix up any formatting or PRs that got sorted to the wrong category
   - Double-check PR attributions (collaborations, hand-offs, etc.)
   - Just make it look nice :)
1. Publish the release.
1. Wait for all the CI madness to happen, for the release to announced to Discord, and for the artifacts to sync to Maven Central.
1. Make sure you're locally updated and on the right major/minor branch (this is the same branch as step 3).
1. Open a PR to merge the minor branch into the major branch. This is only necessary for patch releases.

   `scripts/make-release-prs.sh <old-version> <new-version>`

   e.g. `scripts/make-release-prs.sh v3.5.1 v3.5.2`

1. Open a PR to update the version in the README and documentation site. This is only necessary for stable releases (i.e., not Milestones or Release Candidates)

   `scripts/make-site-pr.sh <old-version> <new-version>`

   e.g. `scripts/make-site-pr.sh v3.5.1 v3.5.2`
