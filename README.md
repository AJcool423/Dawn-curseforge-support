# CurseForge add-on for Dawn

An unofficial, experimental Java add-on that adds a CurseForge catalog panel to an existing Dawn Minecraft launcher installation.

[Project page](https://AJcool423.github.io/Dawn-curseforge-support/) · [Validation](VALIDATION.md) · [CurseForge API application](https://support.curseforge.com/support/solutions/articles/9000208346)

**Status:** Windows prototype tested with Dawn 0.2.0.506. Approved third-party CurseForge API access is required. Successful authenticated catalog browsing and real mod downloads have not yet been verified. This project is not affiliated with or endorsed by Dawn, CurseForge, Overwolf, Mojang, or Microsoft.

## Scope

- Adds a CurseForge menu and owned catalog window inside Dawn's running Java process.
- Searches mods and modpacks, with Minecraft version and loader filters.
- Installs compatible mods and required dependencies into an existing Dawn profile.
- Imports modpacks into new profiles using Dawn's schema version 4.
- Supports Fabric, Forge, and NeoForge profiles. Dawn remains responsible for game launches.
- Uses the official CurseForge API and honors author-disabled third-party downloads.

## API key

The add-on needs an **approved third-party developer API key**. An author upload token is a different credential. Creating a game in the Studios console is not the application path for this tool.

Select **CurseForge → Browse mods and modpacks**, enter your key in the masked field, and choose **Use key for session**. The add-on checks Minecraft catalog access before accepting it. The key is held only in memory and is cleared when Dawn fully exits. It is never included in this repository.

Requests go directly to the official API and validated download hosts over HTTPS. This project operates no proxy or backend. Restricted files stop installation; URLs are not guessed to bypass author restrictions.

## Build and use

Requirements: Windows, PowerShell, and the supported Dawn installation with its bundled Java compiler and Gson dependency. This repository contains original add-on source only; Dawn and third-party binaries are not redistributed.

```powershell
.\build.ps1
.\test.ps1
.\install.ps1
```

Fully quit and reopen Dawn. Its close button may only minimize it. The add-on's **CurseForge** menu appears at the top of the launcher.

The installer checks the supported Dawn JAR hash, backs up the launcher configuration, copies an immutable hash-named add-on JAR, and adds one Java agent option. It does not transform Dawn classes. An update to Dawn may require revalidation before this add-on can load.

To disable it:

```powershell
.\uninstall.ps1
```

Then fully restart Dawn. Other configuration edits, profiles, and installed content are preserved.

## Installation behavior and limits

Files are staged and checked for size and hashes before publication. Existing files are not silently replaced; identical dependencies may be reused. Pack extraction rejects unsafe paths, symbolic links, and duplicate entries. New packs get separate profile directories.

The catalog currently shows the first 50 matching projects. Files must explicitly match the target version and loader. Mod updates and individual mod removal are not implemented. Close Minecraft before changing its profile; on Windows, an uninspectable Java process can also block installation conservatively. Game compatibility and full game launches are not covered by the current tests.

## Project layout

- `src/local/dawn/curseforge/`: UI, agent entry point, API client, and profile installer.
- `tests/`: offline API, UI, installer, and PowerShell lifecycle tests.
- `index.html`: static GitHub Pages project page; no tracking or external scripts.
- `build.ps1`, `test.ps1`, `install.ps1`, `uninstall.ps1`: local build and lifecycle commands.

Bug reports: [GitHub Issues](https://github.com/AJcool423/Dawn-curseforge-support/issues). Never attach API keys, account files, or authentication tokens.
