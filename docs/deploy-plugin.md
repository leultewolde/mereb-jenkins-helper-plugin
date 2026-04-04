# Deploying The IntelliJ Plugin

This plugin is intended for private distribution inside Mereb.

## Build The Artifact

Run the plugin build from `pipeline-configs/mereb-jenkins-schema-pluign`:

```bash
./gradlew clean buildPlugin
```

The installable ZIP is written to `build/distributions/`.

## Versioning

- Keep the plugin version in `build.gradle.kts` aligned with the library release that introduces schema or validation changes.
- The plugin fetches schema content from `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during the build.
- If GitHub is unavailable, the build falls back to the checked-in snapshot at `schema-cache/ci.schema.json`.
- When the schema changes upstream, rebuild and redistribute the plugin so editor validation matches pipeline runtime behavior.

## Internal Distribution

- Upload the generated ZIP to the internal artifact location your team already uses for developer tooling.
- Announce the new version together with a short changelog and the supported IntelliJ build range.

## Installing In IntelliJ

1. Open IntelliJ IDEA.
2. Go to `Settings` / `Preferences` -> `Plugins`.
3. Click the gear icon.
4. Choose `Install Plugin from Disk...`.
5. Select the ZIP from `build/distributions/`.
6. Restart IntelliJ when prompted.

## Rolling Out Updates

- Rebuild the ZIP whenever config schema behavior changes.
- Ask users to replace the installed plugin with the newer ZIP using the same `Install Plugin from Disk...` flow.
- Keep the deployment note with each ZIP so users know which `mereb-jenkins` release it matches.
- The plugin relies on IntelliJ's YAML support for syntax colors, indentation, and reformatting, and adds Mereb-specific icon, schema, and semantic inspection layers on top.
