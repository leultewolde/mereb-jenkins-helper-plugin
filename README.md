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
- Adds context-aware completions for known values such as `recipe`, `delivery.mode`, `release.autoTag.bump`, and environment references inside order lists.
- Adds snippet completions for common blocks such as service starters, image blocks, deploy environments, microfrontend environments, and `release.autoTag`.
- Adds inline documentation for important keys like `recipe`, `delivery.mode`, `release.autoTag.bump`, `deploy.*`, `microfrontend.*`, and `terraform.*`.
- Adds relation-aware editor coloring for linked order entries, unused environment blocks, inactive sections, and ignored runtime keys.
- Suppresses unrelated inspection-based lint on supported Mereb config files, including tools like SonarLint, so Mereb-aware checks remain the primary source of feedback.
- Adds a `Mereb Jenkins` tool window with:
  - `Overview`: recipe, capabilities, notices, safe fixes, and section state
  - `Flow`: derived pipeline stage sequence
  - `Relations`: order-to-environment links, unused definitions, inactive sections, and runtime-ignored links
  - `Upstream`: manual schema freshness check against GitHub
- Adds a dedicated tool window icon and a richer native IntelliJ UI instead of a plain HTML summary.
- Warns when a user edits a legacy YAML filename instead of the preferred `.ci/ci.mjc`.
- Pulls the schema from the GitHub source of truth at `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during build, with a checked-in snapshot as a fallback when the remote is unavailable.

## Using The Plugin

- Open a project that contains a Mereb config plus its sibling `Jenkinsfile`.
- Prefer `.ci/ci.mjc`; legacy `.ci/ci.yml` and `ci.yml` still work.
- Open the `Mereb Jenkins` tool window to inspect the detected project context, derived flow, relations, warnings, and safe fixes.
- If a workspace contains multiple Mereb projects, use the tool window `Project` selector to choose which one to focus on.
- Use normal IntelliJ quick fixes, hover docs, and guided completion inside supported config files to apply repairs and inspect runtime notes without leaving the editor.
- Third-party inspection-based lint is intentionally suppressed for supported Mereb config files. YAML parse errors, bundled schema validation, and Mereb-specific checks still remain.
- Use `Tools -> New Mereb Jenkins Config` to scaffold a new recipe-aware config in the current project.
- Use `Tools -> Migrate Mereb Jenkins Config` to preview a conservative migration to `.ci/ci.mjc`.
- Use `Tools -> How To Use Mereb Jenkins Helper` or `Help -> How To Use Mereb Jenkins Helper` to reopen the built-in guide at any time.

See the full usage guide in [`docs/how-to-use-plugin.md`](docs/how-to-use-plugin.md).

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

Important: the plugin ID was renamed from `org.mereb.intellij.mjc` to `org.mereb.jenkins.helper`.
If you already installed an older build, IntelliJ will treat this as a different plugin. You need one manual cleanup:

1. Uninstall the old `Mereb Jenkins Helper` plugin.
2. Install the current plugin again from the custom repository.

After that one-time reinstall, normal repository-based updates work again.

1. Open IntelliJ IDEA.
2. Go to `Settings` / `Preferences` -> `Plugins`.
3. Click the gear icon.
4. Choose `Manage Plugin Repositories...`.
5. Add:
   `https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml`
6. Search for `Mereb Jenkins Helper` in the Plugins UI and install it.

After the reinstall above, IntelliJ will check the same repository URL for updates normally.

The initial rollout is unsigned. IntelliJ may show a trust warning during install or update.
The GitHub Pages root URL also serves a small landing page that links to the repository feed and current ZIP.

When the plugin first detects a Mereb project in IntelliJ, it also shows a one-time onboarding notification with actions to open the guide and the tool window.

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
