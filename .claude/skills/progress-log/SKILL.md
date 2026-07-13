---
name: progress-log
description: Append a what/why/how entry to progress_report.md recording a change, the issues hit, and how they were resolved. Use after every meaningful change — this is mandatory, not optional.
---

# /progress-log

Append one entry to `progress_report.md`. **After every meaningful change.** Mandatory (CLAUDE.md).

## Steps

1. Read `progress_report.md` and find the last entry number. The new one is `last + 1`, zero-padded
   to three digits.

2. **Append** — never rewrite or delete an existing entry, even a wrong one. A wrong turn we later
   corrected is part of the story, and is usually the most instructive part of it.

3. Use exactly this shape:

```markdown
## [NNN] <Title> — YYYY-MM-DD
**Spec:** SPEC NNNN (or "Setup" / "Fix" / "Refactor")
**What:** what changed, concretely — files, endpoints, tables, behavior.
**Why:** the reason — which requirement (FR-*/NFR-*) or problem drove it.
**How:** the approach taken, the key decisions, and the trade-offs.
**Issues faced:** what broke or surprised us.
**Resolution:** how each issue was fixed, and what we learned.

---
```

## Writing it well

The whole value of this file is that, read end to end, it tells someone **how this project was
actually built** — including the parts that went wrong. That is what makes it worth reading and
what makes it credible.

- **"Why" is the part people skip and the part that matters.** In a year, the *what* is visible in
  the diff and the *how* is visible in the code. The *why* exists nowhere but here.

- **Record the failures.** The approach you tried first and abandoned is more useful than the one
  that worked, because it stops the next person (probably you) from trying it again. If "Issues
  faced" is consistently "None.", the file is lying — and a reader will correctly conclude the
  whole document is decoration.

- **Be specific.** "Fixed a race condition" tells nobody anything. "Two concurrent requests with
  the same idempotency key both passed the `SELECT` existence check before either `INSERT`ed,
  because `SELECT … FOR UPDATE` locks nothing when the row does not exist yet. Replaced
  check-then-insert with a single `INSERT` and caught the unique-violation on the loser" — that is
  a thing someone can learn from.

- Convert relative dates to absolute ones.
