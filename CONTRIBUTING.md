# Contributing

Thanks for your interest in improving the X9 QRCode backend. This project is **source-available**
under the [Matera Source License v1.0](LICENSE.md) (not open source). Contributions are welcome
under the terms below.

## How to contribute

`main` is protected — you cannot push to it directly. Every change goes through a pull request:

1. **Fork** the repository (or create a branch if you have write access).
2. Create a topic branch: `git checkout -b my-change`.
3. Make your change — keep it focused; one logical change per PR.
4. Make sure the build and tests pass: `./mvnw test` (or `make test`).
5. **Sign off** every commit — see [DCO](#developer-certificate-of-origin-dco) below: `git commit -s`.
6. Push your branch and open a **pull request** against `main`.
7. A maintainer reviews and approves. PRs require **1 approval** and are **squash-merged**.

New here? See [RUNNING.md](RUNNING.md) for prerequisites and how to build/run/test.

## Developer Certificate of Origin (DCO)

Instead of a Contributor License Agreement, this project uses the **Developer Certificate of
Origin** — a lightweight, per-commit statement that you have the right to submit your contribution
and agree it may be distributed under this project's license.

**Every commit must be signed off.** Add the sign-off automatically:

```bash
git commit -s -m "your message"
```

This appends a line to the commit message:

```
Signed-off-by: Your Name <your.email@example.com>
```

By signing off you certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/):
in short, the contribution is your own work (or you have the right to submit it) and you agree it is
provided under the **Matera Source License v1.0**. Use your real name and a reachable email.

> Forgot to sign off? Fix the last commit with `git commit --amend -s`, or for several commits
> `git rebase --signoff HEAD~<n>`, then force-push your branch (your branch only — never `main`).

## Ground rules

- Contributions are licensed under the [Matera Source License v1.0](LICENSE.md). Don't add code you
  cannot license under those terms, and don't paste in third-party code with incompatible licensing.
- Keep PRs small and focused, with a clear description of **what** changed and **why**.
- Match the existing architecture — Clean / Hexagonal: the `domain` module has no framework
  dependencies; Spring wiring lives in `infrastructure`.
- Add or update tests for behavior changes; the full suite must be green.
- Never commit secrets, real keystores, or the copyrighted ANSI standard (see `.gitignore`).

## Security issues

Please do **not** open a public issue for vulnerabilities — follow [SECURITY.md](SECURITY.md).

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
