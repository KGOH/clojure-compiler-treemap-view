# Metrics Version Control

## Idea

Commit `metrics.json` to the repo instead of generating during deploy.

## Benefits

- Version history - see how metrics change over commits
- Simpler CI - just commit file, no artifact upload
- GitHub Pages serves directly from repo

## Proposed Structure

```
/
├── viewer.html      (move from resources/)
├── metrics.json     (committed by CI)
└── index.html       (redirect to viewer.html?data=metrics.json)
```

## CI Changes

1. Generate metrics.json at repo root
2. `git add metrics.json && git commit -m "Update metrics"` (skip if no changes)
3. Push to main
4. Remove artifact upload/deploy-pages steps

## GitHub Pages Config

- Deploy from main branch root (not artifact)
- Settings > Pages > Source: "Deploy from a branch" > main / root

## CI Permissions

Needs `contents: write` to push commits back to repo.
