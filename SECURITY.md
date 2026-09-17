# Security Policy

## Supported versions

Security fixes are applied to the `main` branch and to the latest published
release. Older versions are not supported. This is a test-scoped validation
library, but it is consumed by other builds, so treat findings seriously.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Report them privately using GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/eyupmiduck/liquibase_validation/security)
   of the repository.
2. Click **Report a vulnerability** (direct link:
   <https://github.com/eyupmiduck/liquibase_validation/security/advisories/new>).
3. Describe the issue in the private advisory.

Include as much of the following as you can:

- The type of issue (see scope below).
- The affected version or commit.
- Reproduction steps or a minimal proof of concept.
- The impact and how an attacker might exploit it.
- Any suggested mitigation.

## What to expect

- **Acknowledgement** within a few days.
- **Assessment**: we will confirm the issue and its severity, or explain why it
  is not considered a vulnerability.
- **Fix and disclosure**: we will coordinate a disclosure timeline with you.
  Please allow a reasonable window before public disclosure.
- **Credit**: with your permission, we will credit you in the advisory.

This is a personal project maintained on a best-effort basis. There is no bug
bounty program.

## Scope

The library reads a changelog directory: it walks the filesystem and parses
changelog XML. Areas of particular interest:

- **XML parsing** — external entity expansion or other XXE risks when parsing
  changelog XML.
- **Path handling** — path traversal or unexpected filesystem access while
  walking the changelog tree.
- **Denial of service** — pathological input (very large or deeply nested
  changelog trees) that makes validation hang or exhaust memory.

Issues in third-party dependencies should be reported upstream; if a dependency
issue affects this library, we still want to know.

## Secrets

This repository runs automated secret scanning in CI (gitleaks). If you
accidentally commit a credential, treat it as compromised: rotate it first, then
remove it. Deleting the file is not enough, because it remains in git history.
