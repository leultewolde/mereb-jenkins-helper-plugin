# Mereb Jenkins Helper

This IntelliJ plugin adds editor support for Mereb Jenkins pipeline configuration files.

## Features

- Uses a Mereb-branded file icon for `.mjc` configs.
- Treats `*.mjc` files as YAML so IntelliJ formatting, indentation, and structure-aware editing work out of the box.
- Binds the Mereb Jenkins JSON schema to `.ci/ci.mjc`, `.ci/ci.yml`, and `ci.yml`.
- Shows field-level semantic warnings and errors that mirror important runtime rules, including ignored staged-mode options, recipe compatibility issues, missing image config, bad environment order references, and Jenkinsfile path drift.
- Turns common findings into one-click fixes, including adding `recipe`, renaming legacy files to `.ci/ci.mjc`, updating `Jenkinsfile` `configPath`, removing ignored staged-mode keys, trimming invalid environment order values, and inserting an `image.repository` placeholder.
- Adds a `New Mereb Jenkins Config` action that creates recipe-aware starter configs for `build`, `package`, `image`, `service`, `microfrontend`, and `terraform`.
- Adds a conservative migration assistant that previews project-local changes before renaming configs or touching the sibling `Jenkinsfile`.
- Adds snippet completions for common blocks such as service starters, image blocks, deploy environments, microfrontend environments, and `release.autoTag`.
- Adds inline documentation for important keys like `recipe`, `delivery.mode`, `release.autoTag.bump`, `deploy.*`, `microfrontend.*`, and `terraform.*`.
- Adds a `Mereb Jenkins` tool window with:
  - `Preview`: resolved recipe, image/release state, deployment order, ignored fields, and repo-aware warnings
  - `Flow`: derived pipeline stage sequence
  - `Upstream`: manual schema freshness check against GitHub
- Warns when a user edits a legacy YAML filename instead of the preferred `.ci/ci.mjc`.
- Pulls the schema from the GitHub source of truth at `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during build, with a checked-in snapshot as a fallback when the remote is unavailable.

## Using The Plugin

- Open `.ci/ci.mjc`, `.ci/ci.yml`, or `ci.yml` to get schema validation plus Mereb-specific semantic checks.
- Use `Tools -> New Mereb Jenkins Config` to scaffold a new recipe-aware config in the current project.
- Use `Tools -> Migrate Mereb Jenkins Config` from an open config file to preview a conservative migration to `.ci/ci.mjc`.
- Open the `Mereb Jenkins` tool window to inspect the effective recipe, derived flow, and upstream schema freshness.
- Use normal IntelliJ quick fixes and hover docs inside supported config files to apply repairs and inspect runtime notes without leaving the editor.

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
- `./scripts/install-git-hooks.sh` installs the repo-managed pre-commit hook.

## Releases

Releases are push-driven through GitHub Actions and GitHub Pages.

1. Run `./scripts/install-git-hooks.sh` once on your machine.
2. Commit plugin source/config changes normally.
3. The pre-commit hook bumps `build.gradle.kts` when a new release line is needed.
4. Push to `main`.

When relevant plugin files change on `main`, the release workflow:

- reads the plugin version from `build.gradle.kts`
- creates the matching Git tag automatically
- builds the plugin and repository feed
- publishes the repository to GitHub Pages

See [`docs/deploy-plugin.md`](docs/deploy-plugin.md) for the maintainer release flow.
