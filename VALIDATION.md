# Validation

Checked November 16, 2026 against Dawn 0.2.0.506 on Windows.

| Check | Result |
| --- | --- |
| Java build | Passed using Dawn's Java 25 compiler, targeting Java 17 |
| API client fixtures | 92 assertions passed |
| Profile installer fixtures | 43 assertions passed |
| Swing UI fixtures | Passed: background work, filters, targets, key clearing, progress and errors |
| Install/uninstall scripts | Passed: backup, idempotency, preservation of other edits, reinstall and version refusal |
| Native profile schema | Dawn's own codec accepted and reserialized an isolated generated profile |
| Installed UI | CurseForge menu, catalog window and local profile discovery visually verified in Dawn |
| Authenticated catalog/downloads | Not yet verified; API authorization is required |
| Full game launches | Not tested |

API tests cover endpoint parameters, pagination, rejected keys, replacement-key failure, response validation, errors, authenticated official CDN redirects, refusal of foreign redirects, hashes, exact lengths and no-overwrite publication. They use synthetic credentials and offline transport fixtures.

Installer tests cover compatibility, dependencies, identical-file reuse, conflicts, failure cleanup, cycles, modpack schema, ZIP traversal and symlinks, rollback, and conservative Windows process detection. Tests use temporary fixtures rather than real game profiles.

The original installed menu was visually checked before the later key-verification update. The later update passed automated tests and was installed for the next full launcher restart. Its live authorization flow still needs a valid approved key.

No vendor classes, proprietary binaries, user account files, real API keys, or local diagnostic logs are included in this repository.
