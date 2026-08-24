# Remove API Keys from Git History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove exactly two historical DeepSeek API key values and one historical embedding API key value from every Git ref, then update GitHub without exposing the values in logs.

**Architecture:** Read the three values directly from known historical YAML contexts without printing them. Rewrite an isolated mirror clone with `git-filter-repo`, verify the old byte strings have zero matches across all rewritten refs, and only then force-push the mirror. Preserve the dirty working tree separately and reconnect it to the rewritten history after the remote update.

**Tech Stack:** Git, git-filter-repo 2.47 or newer, PowerShell

---

### Task 1: Establish scope and rollback evidence

**Files:**
- Inspect: `src/main/resources/application.yml`
- Inspect: `src/main/resources/application-dev.yml`
- Inspect: `src/main/resources/application-docker.yml`

- [ ] **Step 1: Record repository state**

Run:

```powershell
git status --short --branch
git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/remotes refs/tags
git rev-parse HEAD
```

Expected: one existing modified interview document, `master` at the recorded commit, no tags, and the local interview branch as an ancestor of `master`.

- [ ] **Step 2: Confirm the three distinct API values without printing them**

Read these YAML contexts internally and compare SHA-256 digests only:

```text
0b4f400f83e5:src/main/resources/application.yml -> deepseek.api.key
7bd32ecc2102:src/main/resources/application.yml -> deepseek.api.key
0b4f400f83e5:src/main/resources/application.yml -> embedding.api.key
```

Expected: three distinct digests; shapes are one 35-character DeepSeek value, one 49-character DeepSeek value, and one 35-character embedding value.

### Task 2: Prepare an isolated mirror

**Files:**
- Create temporarily: system temporary mirror directory
- Create temporarily: system temporary rollback mirror directory

- [ ] **Step 1: Install or locate git-filter-repo**

Run:

```powershell
git filter-repo --version
```

Expected: version 2.47 or newer. If absent, install `git-filter-repo` from PyPI with the bundled Python runtime.

- [ ] **Step 2: Clone the authoritative remote twice**

Run from a validated system temporary directory:

```powershell
git clone --mirror https://github.com/Curzsu/ShadowRAG.git cleanup.git
git clone --mirror https://github.com/Curzsu/ShadowRAG.git rollback.git
```

Expected: both mirrors have identical `show-ref` output. Never print an origin URL containing credentials.

### Task 3: Rewrite only the three API values

**Files:**
- Modify temporarily: all Git objects and refs inside `cleanup.git`

- [ ] **Step 1: Load the historical values into process-only environment variables**

Use `git show <commit>:<path>` and a YAML-context parser to assign the three values to process environment variables. Do not print the variables and do not store them in the workspace.

- [ ] **Step 2: Rewrite blobs**

Run `git-filter-repo` with `--sensitive-data-removal` and a blob callback that replaces each of the three byte strings with `***REMOVED***`.

Expected: `git-filter-repo` reports changed commits and produces `.git/filter-repo/commit-map` and `changed-refs` evidence.

- [ ] **Step 3: Clear process variables**

Remove the three process environment variables immediately after the rewrite.

### Task 4: Verify the rewritten mirror

**Files:**
- Inspect: rewritten refs and blobs inside `cleanup.git`
- Inspect: `.git/filter-repo/commit-map`
- Inspect: `.git/filter-repo/changed-refs`

- [ ] **Step 1: Check the three old values**

Re-extract the values from `rollback.git` into process-only variables and scan every rewritten blob and commit message in `cleanup.git`.

Expected: zero matches for each value.

- [ ] **Step 2: Check the current tree**

Run:

```powershell
$oldTree = git -C rollback.git rev-parse 'refs/heads/master^{tree}'
$newTree = git -C cleanup.git rev-parse 'refs/heads/master^{tree}'
if ($oldTree -ne $newTree) { throw 'Rewritten master tree differs from the original master tree.' }
```

Expected: no working-tree content differences at `master`; only commit IDs and historical blobs change.

- [ ] **Step 3: Check ref coverage**

Compare pre-rewrite and post-rewrite ref names.

Expected: the same branch and tag names exist; affected commit IDs map to nonzero rewritten IDs.

### Task 5: Update GitHub and reconnect the local clone

**Files:**
- Modify: GitHub repository refs
- Modify: local Git refs and reflogs
- Preserve: existing modified interview document

- [ ] **Step 1: Force-push the verified mirror**

Run only after explicit approval:

```powershell
git push --force --mirror origin
```

Expected: all writable branches and tags update; any failures are limited to GitHub read-only pull-request refs.

- [ ] **Step 2: Preserve the dirty document and reconnect local refs**

Stash the existing tracked modification, fetch the rewritten remote with an explicit force refspec, reset `master` to rewritten `origin/master`, recreate the local interview branch using `commit-map`, and pop the stash.

Expected: the same interview document remains modified and all tracked file contents match their pre-cleanup state.

- [ ] **Step 3: Remove local references to old history**

After the stash is restored and checked, expire old reflogs and prune unreachable objects.

Expected: scanning all local refs and objects finds zero instances of the three API values.

- [ ] **Step 4: Final remote verification**

Fetch the remote into a new temporary mirror and repeat the zero-match scan.

Expected: all three values have zero matches in the fresh remote mirror.
