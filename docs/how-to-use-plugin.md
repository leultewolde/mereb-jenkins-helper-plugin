# How To Use Mereb Jenkins Helper

This plugin is meant to help you work with one Mereb project unit at a time:

- one Mereb config file
- one sibling `Jenkinsfile`

If you are upgrading from an older local build of this plugin, uninstall the old plugin once and then install the current one again. The plugin ID changed, so IntelliJ treats it as a separate plugin.

The preferred config path is:

```text
.ci/ci.mjc
```

Legacy files are still supported:

- `.ci/ci.yml`
- `ci.yml`

## Quick Start

1. Open a project that contains a Mereb config and `Jenkinsfile`.
2. Open the `Mereb Jenkins` tool window from the IntelliJ tool window bar.
3. Let the plugin auto-detect the project pair.
4. If the workspace has multiple Mereb projects, use the tool window `Project` selector to choose one.

## What The Tool Window Does

The tool window is now project-context driven. It does not require the config file tab to be active.

- `Overview`
  Shows the resolved recipe, enabled capabilities, warnings, safe fixes, and section status.
- `Flow`
  Shows the derived runtime sequence for the focused config.
- `Relations`
  Shows how order lists relate to environment blocks, plus missing or unused definitions.
- `Upstream`
  Lets you manually check whether the bundled schema is current with the GitHub source of truth.

The tool window refreshes automatically when:

- the focused config changes
- the sibling `Jenkinsfile` changes
- the active editor selection changes in a way that affects project focus

## What To Do In The Editor

Inside supported config files, the plugin adds:

- schema validation
- Mereb-specific semantic validation
- quick fixes
- value completion for known enum-like fields
- dynamic completion for environment names in order lists
- hover docs for important keys
- inline block descriptions for important sections

It also suppresses unrelated inspection-based lint for these files, including tools like SonarLint. The intent is that Mereb config files are interpreted primarily by the bundled schema plus the plugin’s Mereb-aware rules instead of generic YAML lint. YAML parser errors still remain.

Examples of fields with guided completion:

- `recipe`
- `delivery.mode`
- `release.autoTag.bump`
- environment names inside `deploy.order`, `microfrontend.order`, and `terraform.order`

## Common Workflows

### Create a new config

Use:

```text
Tools -> New Mereb Jenkins Config
```

This creates a starter `.ci/ci.mjc` file for one of these recipes:

- `build`
- `package`
- `image`
- `service`
- `microfrontend`
- `terraform`

### Migrate a legacy config

Use:

```text
Tools -> Migrate Mereb Jenkins Config
```

The migration assistant is conservative. It can:

- rename `.ci/ci.yml` or `ci.yml` to `.ci/ci.mjc`
- add explicit `recipe`
- update an explicit `configPath` in the sibling `Jenkinsfile`
- remove or flag keys that runtime ignores in staged mode

### Apply quick fixes

When the plugin highlights an issue, use IntelliJ quick fixes to handle things like:

- missing `recipe`
- invalid recipe spellings
- missing `image.repository`
- invalid environment order values
- legacy config file names

## Onboarding In IntelliJ

When the plugin detects a Mereb project for the first time in a given project and plugin version, it shows a startup notification with:

- `Show How To Use`
- `Open Tool Window`

You can reopen the guide later from:

```text
Tools -> How To Use Mereb Jenkins Helper
```

or:

```text
Help -> How To Use Mereb Jenkins Helper
```
