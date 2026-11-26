# The release checklist

Before every release, in this order:

- [ ] `git pull --ff-only` — the working tree is clean (`git status`)
- [ ] a backup is taken: `bash tools/backup.sh /var/backups/ultibiz`
- [ ] the restore drill is green: `bash tools/restore-drill.sh <the newest dump>`
- [ ] `bash tools/release-check.sh` — the i18n audit, the build with the tests
- [ ] the migrations are reviewed: they only add tables and columns
- [ ] the secrets are in `.env` (mode 600), not in the repository
- [ ] the deploy: the WAR is copied with `ROOT.war.bak` in place
- [ ] the smoke test: the login, the new pages, one write in the new module
- [ ] the scheduler logs are clean: `journalctl -u ultibiz-tomcat --since "10 min ago" | grep -i error`
- [ ] the release notes are updated
