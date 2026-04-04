# Mereb Jenkins Helper

This IntelliJ plugin adds editor support for Mereb Jenkins pipeline configuration files.

## Features

- Uses a Mereb-branded file icon for `.mjc` configs.
- Treats `*.mjc` files as YAML so IntelliJ formatting, indentation, and structure-aware editing work out of the box.
- Binds the Mereb Jenkins JSON schema to `.ci/ci.mjc`, `.ci/ci.yml`, and `ci.yml`.
- Warns when a user edits a legacy YAML filename instead of the preferred `.ci/ci.mjc`.
- Shows semantic warnings and errors that mirror important runtime rules, including ignored staged-mode options and recipe compatibility issues.
- Pulls the schema from the GitHub source of truth at `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during build, with a checked-in snapshot as a fallback when the remote is unavailable.

## Build

```bash
./gradlew buildPlugin
```

The built plugin ZIP is written to `build/distributions/`.

## Development

- `./gradlew runIde` launches a sandbox IntelliJ instance with the plugin installed.
- `./gradlew test` runs the plugin test suite.

See [`docs/deploy-plugin.md`](docs/deploy-plugin.md) for internal distribution steps.
