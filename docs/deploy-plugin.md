# Deploying The IntelliJ Plugin

This plugin is delivered through a GitHub Pages custom plugin repository hosted from this repo.

## One-Time Repository Setup

Before the first release:

1. Push this repo to GitHub.
2. In GitHub, open `Settings -> Pages`.
3. Set `Build and deployment` to `GitHub Actions`.
4. Keep the repo public if you want the cheapest, simplest distribution path.
5. Run `./scripts/install-git-hooks.sh` once on every maintainer machine so version bumps happen before commit.

The published repository URL will be:

```text
https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml
```

The GitHub Pages root URL is also usable as a human landing page:

```text
https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/
```

## Release Process

Every published plugin version is driven by pushes to `main`.

The plugin ID is now:

```text
org.mereb.jenkins.helper
```

This replaced the older ID `org.mereb.intellij.mjc`. Because IntelliJ keys updates by plugin ID, users on the old ID will need one manual uninstall/reinstall when this change is rolled out.

1. Commit plugin source/config changes.
2. The pre-commit hook bumps `build.gradle.kts` when the current version has already been released.
3. Push to `main`.

The GitHub Actions workflow at `.github/workflows/release-plugin.yml` will:

1. run only when plugin source/config files change on `main`
2. read the version from `build.gradle.kts`
3. create and push the matching Git tag in the format `vX.Y.Z`
4. run `./gradlew clean test buildPlugin generateCustomPluginRepository`
5. generate `updatePlugins.xml`
6. publish `updatePlugins.xml`, `index.html`, and the built ZIP to GitHub Pages

The published Pages artifact contains:

- `updatePlugins.xml`
- `plugins/mereb-jenkins-helper-<version>.zip`

Only the current release is kept in the update feed.

## Local Validation Before Pushing

Run this from `pipeline-configs/mereb-jenkins-schema-pluign`:

```bash
./gradlew clean test buildPlugin generateCustomPluginRepository \
  -PcustomPluginRepositoryBaseUrl=https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin
```

Check:

- `build/distributions/mereb-jenkins-helper-<version>.zip` exists
- `build/custom-plugin-repository/updatePlugins.xml` points at the same versioned ZIP
- `build/custom-plugin-repository/index.html` links to the same repository feed and ZIP
- the generated XML contains the expected plugin id, version, and IntelliJ build range

## Version Management

- The version source of truth remains `build.gradle.kts`.
- The repo-managed pre-commit hook bumps the patch version only when:
  - staged changes touch plugin source/config files, and
  - the current version already has a Git tag like `v0.1.4`
- If the current version has not been released yet, the hook leaves it unchanged.
- If you change the version line in `build.gradle.kts` yourself, the hook will not overwrite it.

## End-User Installation

Users install the plugin by adding the repository URL once in IntelliJ:

If the user already has an older build installed under plugin ID `org.mereb.intellij.mjc`, they must first:

1. Uninstall the old plugin.
2. Restart IntelliJ if prompted.
3. Install the current plugin from the custom repository.

This one-time reinstall is required because IntelliJ treats the new plugin ID as a different plugin.

1. Open IntelliJ IDEA.
2. Go to `Settings` / `Preferences` -> `Plugins`.
3. Click the gear icon.
4. Choose `Manage Plugin Repositories...`.
5. Add:
   `https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml`
6. Search for `Mereb Jenkins Helper` in the Plugins UI and install it.

After that, IntelliJ checks the same repository URL for updates.

After the plugin is installed and a project containing a Mereb config is opened, the plugin shows a one-time onboarding notification for that project and plugin version. The notification links to:

- the built-in `How To Use` guide
- the `Mereb Jenkins` tool window

The same guide is also available from:

- `Tools -> How To Use Mereb Jenkins Helper`
- `Help -> How To Use Mereb Jenkins Helper`

## Signing And Trust Warning

This first version is intentionally unsigned to keep setup and hosting costs low.

Because of that, IntelliJ may show a trust warning during install or update. That is expected with the current rollout model.

## Notes

- The plugin fetches schema content from `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during the build.
- If GitHub is unavailable, the build falls back to `schema-cache/ci.schema.json`.
- The plugin relies on IntelliJ's YAML support for syntax colors, indentation, and reformatting, and adds Mereb-specific icon, schema, and semantic inspection layers on top.
