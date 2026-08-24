# Project Conventions — Workflow, Branch, Commit, Issue/PR Templates

Development pipeline and collaboration conventions for AI agents and contributors.
Follow these formats exactly when creating branches, commits, issues, and pull requests.

## GitHub Template Locations

The actual GitHub templates are maintained in these paths:

- Feature issue template → [`.github/ISSUE_TEMPLATE/기능-개발-템플릿.md`](.github/ISSUE_TEMPLATE/기능-개발-템플릿.md)
- Fix issue template → [`.github/ISSUE_TEMPLATE/버그-수정-템플릿.md`](.github/ISSUE_TEMPLATE/버그-수정-템플릿.md)
- Pull Request template → [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)

This document defines the conventions that those templates use. Keep the templates and this document synchronized when either one changes.

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

<!-- Describe what the feature is and why it is needed. -->

## 작업 상세 내용

<!-- List the detailed implementation and logic items. -->
```

### Fix issue

```markdown
## 버그 설명

<!-- Describe what is wrong and the situation in which it occurs. -->

## 수정 사항

<!-- Describe what will be changed to fix the bug. -->
```

## GitHub Pull Request Template

Replace the `#` in `- Closes #` with the actual issue number that the PR resolves. If multiple issues are being closed, use the closing keyword for each issue.

```markdown
## 개요 (Overview)

<!-- Describe what this PR does in two or three lines.
     Help reviewers understand what to expect before reading the code. -->

---

## 주요 변경 사항 (Key Changes)

<!-- Group changes by what was changed and why, rather than listing files.
     GitHub already shows the diff, so include the reasoning that the diff does not show. -->

### 1.

### 2.

---

## 검증 결과 (Verification)

<!-- Optional. Include screenshots for UI work or requests and responses for APIs. -->

```bash

```

---

## 관련 이슈 (Related Issues)

<!-- Use Closes / Fixes / Resolves followed by the issue number.
     The issue is closed automatically when the PR is merged.

     For multiple issues, add a closing keyword to each issue.
       O  Closes #12, closes #13
       X  Closes #12, #13     ← #13 will not be closed -->

- Closes #
```
