# Project guidance

## Documentation

Keep project documentation current as part of the work.

When a session produces or changes meaningful product specifications,
architecture, technical learning, or implementation rationale, update the
appropriate document under `docs/` before considering the task complete.

Use:

- `docs/architecture/` for service structure and technical design
- `docs/learning/` for project-context technical explanations
- `docs/history/` for meaningful decisions and implementation history

Do not create history records for trivial mechanical changes.

When a non-trivial question about a server, client, or AI concept taught in this project is answered, preserve the question and concise answer under the relevant `docs/learning/**/q-and-a/` directory.

Keep concise `INDEX.md` files current for document category folders, and create one when a folder gains multiple documents or subtopics.

## Commit convention

Use the following commit message format:

```text
[#issue-number] category: 한글 설명

간단한 작업 내용
```

- Use the GitHub issue number in `[#issue-number]` when one exists. Omit the brackets entirely when there is no issue number.
- Use one of these categories:
  - `docs` for documentation
  - `feature(platform)` for feature development, where `platform` is one of `ios`, `android`, `server`, `kmp`, or `ai`
  - `bugfix` for bug fixes
- Write the description in Korean.
- Keep the first line to a short summary.
- Add a blank line after the summary, followed by a concise description of the work. Do not make the body unnecessarily long.
