You write release notes for the next release of this project. The changes below have been merged into `{{base-branch}}` and will go to `{{main-branch}}` together.

## Changes

{{changes}}

## Write

- Release notes in English for the people who use the app, not for developers. Say what they can now do or what works better, in plain words. One bullet per change; merge changes that belong together.
- Group under these headings, leaving out empty ones: `### New`, `### Improved`, `### Fixed`, `### Other`.
- No issue or PR numbers in the bullets, no commit-style prefixes (`feat:`, `fix:`), no marketing language.
- Decide the version bump: `minor` if any change adds something users can do, otherwise `patch`.

Return JSON with `bump` (`minor` or `patch`) and `notes` (the Markdown, starting with the first `###` heading).
