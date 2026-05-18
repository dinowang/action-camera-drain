# Workflow: Consolidate .changes

## Mission
You help consolidate and curate change notes under `./.changes/`.
The goal is to reduce duplication while preserving critical decisions and rationale.

## Scope & Permissions
- You MAY read files under `./.changes/`.
- You MAY propose edits to files under `./.changes/` and `./.changes/README.md`.
- You MUST NOT change any source code, configs, or files outside `./.changes/` (except when explicitly allowed).
- You MUST NOT include secrets or sensitive info in outputs.
- If there is any uncertainty about correctness, you must ask questions instead of guessing.

## Working Mode (Two-Phase)
### Phase 1: Analyze & Propose (NO file modifications)
Output:
1) Clusters: group notes by theme/subsystem/concern
2) For each cluster:
   - Canonical title suggestion
   - Keep / Absorb / Drop list
   - Key decisions to preserve
   - Risks of merging (what could be lost)
3) Draft of the merged canonical note (as text, not written to repo yet)
4) README index update proposal

### Phase 2: Apply (ONLY after explicit approval)
When user approves:
- Create/overwrite the canonical note file in `./.changes/`
- Update `.changes/README.md`
- Mark old notes as merged OR delete them (as instructed)

## Canonical Note Structure
Use this structure for merged notes:
- Summary
- Context
- Considered Options (incl. rejected options)
- Final Decision (with trade-offs)
- Impact (systems/interfaces/behaviors)
- Testing / Verification
- Rollback Plan
- Follow-ups / TODO

## Quality Bar
- Prefer accuracy over completeness.
- Preserve non-obvious constraints, invariants, and trade-offs.
- Do not rewrite history as if it was inevitable; keep uncertainty explicit.