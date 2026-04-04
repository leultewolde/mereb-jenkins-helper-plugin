# Deploying The IntelliJ Plugin

This plugin is delivered through a GitHub Pages custom plugin repository hosted from this repo.

## One-Time Repository Setup

Before the first release:

1. Push this repo to GitHub.
2. In GitHub, open `Settings -> Pages`.
3. Set `Build and deployment` to `GitHub Actions`.
4. Keep the repo public if you want the cheapest, simplest distribution path.

The published repository URL will be:

```text
https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml
```

The GitHub Pages root URL is also usable as a human landing page:

```text
https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/
```

## Release Process

Every published plugin version is driven by a Git tag.

1. Update `version` in `build.gradle.kts`.
2. Commit and push the version change.
3. Create a matching tag in the format `vX.Y.Z`.
4. Push the tag.

Example:

```bash
git tag v0.1.3
git push origin v0.1.3
```

The GitHub Actions workflow at `.github/workflows/release-plugin.yml` will:

1. validate that the tag matches `build.gradle.kts`
2. run `./gradlew clean test buildPlugin generateCustomPluginRepository`
3. generate `updatePlugins.xml`
4. publish `updatePlugins.xml` and the built ZIP to GitHub Pages

The published Pages artifact contains:

- `updatePlugins.xml`
- `plugins/mereb-jenkins-helper-<version>.zip`

Only the current release is kept in the update feed.

## Local Validation Before Tagging

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

## End-User Installation

Users install the plugin by adding the repository URL once in IntelliJ:

1. Open IntelliJ IDEA.
2. Go to `Settings` / `Preferences` -> `Plugins`.
3. Click the gear icon.
4. Choose `Manage Plugin Repositories...`.
5. Add:
   `https://<github-user-or-org>.github.io/mereb-jenkins-helper-plugin/updatePlugins.xml`
6. Search for `Mereb Jenkins Helper` in the Plugins UI and install it.

After that, IntelliJ checks the same repository URL for updates.

## Signing And Trust Warning

This first version is intentionally unsigned to keep setup and hosting costs low.

Because of that, IntelliJ may show a trust warning during install or update. That is expected with the current rollout model.

## Notes

- The plugin fetches schema content from `https://github.com/leultewolde/mereb-jenkins/blob/main/docs/ci.schema.json` during the build.
- If GitHub is unavailable, the build falls back to `schema-cache/ci.schema.json`.
- The plugin relies on IntelliJ's YAML support for syntax colors, indentation, and reformatting, and adds Mereb-specific icon, schema, and semantic inspection layers on top.
