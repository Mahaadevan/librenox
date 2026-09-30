# Changelog

## Unreleased

- Adopted the attached single-file terminal implementation.
- Added checksum verification for Paper, Modrinth, and GitHub assets when
  publishers provide hashes, with trust-on-first-use manifests otherwise.
- Added safe ZIP and legacy-tar extraction checks.
- Made EULA acceptance explicit: the user must type `yes`.
- Refused public tunnel startup when `online-mode` is disabled.
- Removed the Paper-incompatible mod installer.
- Added backup free-space checks and incomplete-backup reports.
- Added standard-library unit tests and Windows/Ubuntu/macOS CI.
