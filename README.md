# Mereb Jenkins Helper

This IntelliJ plugin adds editor support for Mereb Jenkins pipeline configuration files.

## Features

- Uses a Mereb-branded file icon for `.mjc` configs.
- Treats `*.mjc` files as YAML so IntelliJ formatting, indentation, and structure-aware editing work out of the box.
- Binds the Mereb Jenkins JSON schema to `.ci/ci.mjc`, `.ci/ci.yml`, and `ci.yml`.
- Warns when a user edits a legacy YAML filename instead of the preferred `.ci/ci.mjc`.
- Shows semantic warnings and errors that mirror important runtime rules, including ignored staged-mode options and recipe compatibility issues.
- Pulls the schema from the GitHub source of truth at `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during build, with a checked-in snapshot as a fallback when the remote is unavailable.

## Local Build

```bash
./gradlew buildPlugin
```

The built plugin ZIP is written to `build/distributions/`.

To generate a Pages-ready custom plugin repository locally:

```bash
./gradlew clean test buildPlugin generateCustomPluginRepository \
  -PcustomPluginRepositoryBaseUrl=https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin
```

That writes these files to `build/custom-plugin-repository/`:

- `updatePlugins.xml`
- `plugins/mereb-jenkins-helper-<version>.zip`

## Install And Update In IntelliJ

Use a custom plugin repository instead of `Install Plugin from Disk`.

1. Open IntelliJ IDEA.
2. Go to `Settings` / `Preferences` -> `Plugins`.
3. Click the gear icon.
4. Choose `Manage Plugin Repositories...`.
5. Add:
   `https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml`
6. Search for `Mereb Jenkins Helper` in the Plugins UI and install it.

After that one-time setup, IntelliJ will check the same repository URL for updates.

The initial rollout is unsigned. IntelliJ may show a trust warning during install or update.
The GitHub Pages root URL also serves a small landing page that links to the repository feed and current ZIP.

## Development

- `./gradlew runIde` launches a sandbox IntelliJ instance with the plugin installed.
- `./gradlew test` runs the plugin test suite.

## Releases

Releases are tag-driven through GitHub Actions and GitHub Pages.

1. Bump `version` in `build.gradle.kts`.
2. Commit and push the change.
3. Create and push a matching tag like `v0.1.3`.

The release workflow validates that the tag matches the Gradle version, builds the plugin, generates `updatePlugins.xml`, and publishes the repository to GitHub Pages.

See [`docs/deploy-plugin.md`](docs/deploy-plugin.md) for the maintainer release flow.
