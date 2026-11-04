# Modeled update with real author inputs

Run from the source checkout with a verified original archive and official locked payloads already cached:

```powershell
python tools/modeled-update/verify_modeled_update.py --cache PATH_TO_CACHE --out PATH_TO_NEW_ARTIFACT_DIRECTORY
```

This stand copies the current authored checkout into independent candidate directories. It rewrites the actual pinned author archive three times, changing only its synthetic manifest version and one trailing LF in an existing config. The synthetic lock has no official file ID and uses a `fixture://` URL. A process-local interceptor handles only that exact local fixture archive; every dependency still passes the normal official URL, size and SHA256 restoration logic. Archive membership, full preparation, native mod ID provenance and overlay verification run normally.

The non-target OpenLoader config change must survive with every mandatory overlay operation reapplied. A changed FancyMenu target and changed required GlobalSpawn config must each fail before receiving any active gameplay files. The report includes archive fingerprints, exact overlay output hashes and guards. It compares the current source gameplay/locks and public release inventory before and after. Concurrent QA documentation is outside that source control hash. `--offline` explicitly omits the public-release observation and makes no remote-immutability claim.

These are synthetic update fixtures, not a new official author version, a runtime compatibility test, a personal-world backup or a publication command. Output stays in an ignored artifacts directory outside the source checkout. Rerun after changing the mandatory overlay; older reports remain bounded to their recorded input hashes and operation count.

`reference-proof.json` is the bounded report for the original 1.8.1 snapshot with helper 1.0.2 and nine mandatory operations. All three cases passed; the public release was absent before and after this pre-publication exercise. It contains no personal paths or raw runtime logs.
