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
5. Use `Connect Jenkins` to save your Jenkins URL, username, and API token once.
6. Let the plugin auto-search Jenkins for the project job, or choose one if several jobs match.

## What The Tool Window Does

The tool window is now project-context driven. It does not require the config file tab to be active.

- `Overview`
  Shows the resolved recipe, enabled capabilities, warnings, safe fixes, and section status.
- `Jenkins`
  Shows Jenkins connection state, the mapped job, recent runs, stage status, pending approvals, and artifact links.
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
- Jenkins live data changes while the tool window is visible and a project is mapped

## Connecting To Jenkins

The plugin uses Jenkins `username + API token` authentication and stores the token in IntelliJ Password Safe.

Use one of these entry points:

- `Connect Jenkins` in the `Jenkins` tool window tab
- `Settings / Preferences -> Tools -> Mereb Jenkins Helper`

The connection flow lets you:

- set the Jenkins base URL
- set your Jenkins username
- paste an API token
- open `${baseUrl}/me/configure` to create a token in Jenkins
- test the connection before saving

The plugin does not write Jenkins connection data into `.ci/ci.mjc` or any git-tracked file.

## Job Discovery

After Jenkins is connected, the plugin tries to resolve a job for the currently focused project root.

- If a saved mapping already exists and still resolves, it is reused.
- If no mapping exists, the plugin searches visible Jenkins jobs and tries to auto-select one exact match.
- If multiple jobs are plausible, the plugin shows a picker and remembers your choice locally for that project root.
- If the mapped job later disappears, the plugin drops the stale mapping and asks you to remap it.

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

### Use live Jenkins data

Once the plugin is connected and a job is mapped, the `Jenkins` tab shows:

- recent runs
- current or latest pipeline stages
- pending input or approval markers
- artifact links when Jenkins exposes them
- direct `Open in Jenkins` navigation

The plugin is read-only in this release. It does not trigger builds or approve paused pipelines.

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
