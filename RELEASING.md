# Release procedure

1. Press the "Draft a new release" button in GitHub.
1. Create a new tag with the appropriate name (e.g. v3.5.1); also name the release in GitHub accordingly.
1. Make sure you're targeting the appropriate branch! (Probably the minor branch.)
1. Press the "Generate new release notes" button in GitHub.
1. Manually edit the generated release notes:
   - Increment and transliterate the release counter
   - Cargo-cult the old release notes header from the last minor release (including any warnings)
   - Add any special notes to the release header (any major changes or fixes that need calling out)
   - Fix up any formatting or PRs that got sorted to the wrong category
   - Double-check PR attributions (collaborations, hand-offs, etc.)
   - Just make it look nice :)
1. Press the "Publish release" green button in GitHub.
1. Wait for all the CI madness to happen, for the release to announced to Discord, and for the artifacts to sync to Maven Central (all of this should happen automatically).
1. Make sure you're locally updated and on the right major/minor branch (this is the same branch as step 3).
1. Run the following script to open two PRs to (1) update the version in the README/docs of the minor branch and then (2) merge the minor branch into the major branch. This script currently works only for patch releases and not for minor releases, milestones, or release candidates, which need special handling.

   `scripts/make-release-prs.sh <old-version> <new-version>`

   e.g. `scripts/make-release-prs.sh v3.5.1 v3.5.2`

1. Open a PR to the docs branch to update the landing page. This is only necessary for stable releases (i.e., not milestones or release candidates)

   `scripts/make-site-pr.sh <old-version> <new-version>`

   e.g. `scripts/make-site-pr.sh v3.5.1 v3.5.2`
