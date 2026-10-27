# Skyblock Recipe Viewer (SBI) | Fabric MC 26.2
This is not a re-creation of Firmament just some of its features without snitching the users mods list

# AI Disclosure
This project was created entirely by AI by MGHusky

# Dependencies
- MC 26.2
- Fabric Loader 0.19.3+
- FabricAPI

# Build versions

`mod_version` in `gradle.properties` is the release version, using `MAJOR.MINOR.PATCH`:

- Bug fixes increment PATCH: `1.0.5` to `1.0.6`.
- Feature additions increment MINOR and reset PATCH: `1.0.5` to `1.1.0`.
- Minecraft version changes increment MAJOR and reset MINOR and PATCH: `1.3.5` to `2.0.0`.

Each component can grow beyond 9: `1.0.9` becomes `1.0.10`, without carrying into MINOR.
For a set of changes, apply the highest applicable bump once. Rebuilding the same changes
does not require another version bump.

Every build appends its short Git commit hash, for example `1.0.3+7574977`. Local builds
with uncommitted source or build input changes add `.dirty`. Generated files do not
trigger this marker. Builds from a source archive without Git metadata use `+unknown`.
The full version appears in the jar filename, the mod metadata, the startup log, and
`/sbi version`.

# Credits
- MGHusky - The person who originally created this project
- https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO 
- https://github.com/shedaniel/RoughlyEnoughItems
- https://github.com/FirmamentMC/Firmament
