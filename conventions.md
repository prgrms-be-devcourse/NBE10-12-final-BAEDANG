# Project Conventions — Workflow, Branch, Commit, Issue/PR Templates

Development pipeline and collaboration conventions for AI agents and contributors.
Follow these formats exactly when creating branches, commits, issues, and pull requests.

## Development Pipeline

Feature planning → feature design (incl. edge-case analysis) → implementation → tests (integration: user-case based / unit: edge-case based / acceptance: E2E) → merge.

## Branch Naming

GitFlow: `main` ← `develop` ← `feature/fix` branches.

- Feature/fix branch names use **snake_case**.
- `feat/<domain>_<feature>` — feature implementation + test code
- `fix/<domain>_<feature>` — bug fix (hotfix), refactoring

```text
feat/stock_scheduling        + commits: feat / test / fix / chore
fix/stock_scheduling         + commits: hotfix / refactor
```

## Commit Format

```text
<type>: <message>
```

| Type       | Use                                                            |
| ---------- | -------------------------------------------------------------- |
| `feat`     | New feature implemented                                        |
| `fix`      | Structural change mid-implementation; feature not yet complete |
| `chore`    | Docs edits, renames, and other minor work                      |
| `test`     | Test code for a feature                                        |
| `refactor` | Cleanup that could wait but is done now (already working)      |
| `hotfix`   | Bug fix that will break production if not applied (post-merge) |

## GitHub Issue Templates

### Feature issue

```markdown
## 기능 설명

<!-- Overall description of the feature -->

## 작업 상세 내용

<!-- Detailed logic / implementation items -->
```

### Fix issue

```markdown
## 버그 설명

<!-- What the bug is + the situation where it occurs -->

## 수정 사항

<!-- What will be changed -->
```

## GitHub Pull Request Template

Auto-close the linked issue with `Closes #N`. Replace `N` with the number of the issue the PR resolves.

```markdown
## 개요 (Overview)

<!-- What this PR is about -->

## 주요 변경 사항 (Key Changes)

### 1. ...

<!-- What was done in this PR -->

## 검증 결과 (Verification)

<!-- Test results, run results (screenshots) — optional -->

## 관련 이슈 (Related Issues)

Closes #N
```
