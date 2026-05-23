# Publishing workflow

`movebank-mirror-api` ships a single artifact:

| Artifact                                                       | Channel          |
|----------------------------------------------------------------|------------------|
| `movebank-mirror-api-boot-<version>.{tar,zip}` distribution     | GitHub Releases  |

This is an **application**, not a library — Spring Boot replay server
distributed as a self-contained executable-jar archive. Not published
to Maven Central; that's for libraries downstream consumers depend on.
For the family-wide pattern, the Maven Central path is documented in
[`movebank-api-client/PUBLISHING.md`](https://github.com/mcb77/movebank-api-client/blob/master/PUBLISHING.md)
and [`movebank-mirror/PUBLISHING.md`](https://github.com/mcb77/movebank-mirror/blob/master/PUBLISHING.md).

---

## Part 1. One-time setup

There's essentially none. The release artifact lands on GitHub
Releases via `gh release create`, which uses the `gh` CLI's existing
authentication.

If `gh` isn't authenticated yet:

```bash
gh auth login
```

That's it. No PGP key, no Docker Hub token, no Sonatype account.

---

## Part 2. Cutting a release

### 2.1 Pre-flight: dependency check

`movebank-mirror-api` depends on `movebank-mirror` (transitively on
`movebank-api-client`) at runtime. Both must be on Maven Central
before this release, or downstream consumers building from source
will fail at dependency resolution.

```bash
curl -sI https://repo1.maven.org/maven2/de/firetail/compat/movebank/movebank-mirror/<version>/movebank-mirror-<version>.pom \
  | head -1   # expect: HTTP/2 200

curl -sI https://repo1.maven.org/maven2/de/firetail/compat/movebank/movebank-api-client/<version>/movebank-api-client-<version>.pom \
  | head -1   # expect: HTTP/2 200
```

### 2.2 Pre-flight: disable local includeBuilds

`gradle.properties` exposes `localMovebankApiClient` and
`localMovebankMirror` toggles for sibling-checkout development. **Both
must be commented out at release time** — otherwise the build uses
sibling source instead of Maven Central artifacts, and contributors
who clone this repo will fail to build without the same sibling
layout.

```bash
grep '^local' gradle.properties
# all matching lines must be commented (start with #).
```

### 2.3 Update the version

In `build.gradle`:

```groovy
version = '0.0.2'                    // bump per release
```

The version flows through to the archive filenames
(`movebank-mirror-api-boot-<version>.tar`).

### 2.4 Clean build

```bash
./gradlew --stop      # release any cached daemon
./gradlew clean build
```

Includes `DirectReadIntegrationTest` (in-process HTTP round-trip
against a tmp mirror). A failing test here is a release blocker.

### 2.5 Run the R-client regression suite

The compatibility harness lives at
[`compatibility/move-r/`](compatibility/move-r/) and
[`compatibility/move2-r/`](compatibility/move2-r/). Both must pass
before tagging.

```bash
# Start the freshly-built server against a populated mirror.
./gradlew bootRun --args='-d /tmp/movebank-mirror -p 8080 -b 0.0.0.0' &

# Run both clients against it via the rocker image.
docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -e MOVEBANK_MIRROR_API_URL=http://host.docker.internal:8080/movebank \
    -v "$PWD/compatibility/move-r":/work:ro -w /work \
    mcb77/movebank-rocker:latest Rscript verify.R
# expect: 4/4 checks passed

docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -e MOVEBANK_MIRROR_API_URL=http://host.docker.internal:8080/movebank \
    -v "$PWD/compatibility/move2-r":/work:ro -w /work \
    mcb77/movebank-rocker:latest Rscript verify.R
# expect: 4/4 checks passed

# Stop the bootRun
PID=$(ss -lntp | grep ':8080' | grep -oP 'pid=\K\d+'); kill "$PID"
```

8/8 across both clients is the release gate. Anything less = stop, fix,
re-verify. (For local development without docker, you can run the R
scripts directly against any R 4.x install with `move` / `move2`
installed; the docker path is the more reproducible one.)

### 2.6 Build the release distribution

```bash
./gradlew bootDistTar bootDistZip
ls build/distributions/
# → movebank-mirror-api-boot-<version>.tar
# → movebank-mirror-api-boot-<version>.zip
# (plus *-<version>.tar/.zip without the -boot prefix, which is the
#  classic application-plugin layout — we attach only the boot variants
#  to the GitHub Release.)
```

### 2.7 Smoke-test the unpacked distribution

Catches "the archive packaged something broken" before publishing:

```bash
rm -rf /tmp/movebank-mirror-api-smoketest
mkdir /tmp/movebank-mirror-api-smoketest
tar -xf build/distributions/movebank-mirror-api-boot-<version>.tar \
    -C /tmp/movebank-mirror-api-smoketest

/tmp/movebank-mirror-api-smoketest/movebank-mirror-api-boot-<version>/bin/movebank-mirror-api --help
```

Should print the usage banner. If it doesn't, the archive is bad
(missing classpath, wrong main class, missing launcher) — debug
before publishing.

### 2.8 Tag the release

```bash
git tag -a v<version> -m "v<version>"
git push origin v<version>
```

The tag name is the version with a leading `v`. No GitHub Actions
workflow currently triggers on this; the release is a manual `gh
release create` step (§2.9).

### 2.9 Create the GitHub Release

```bash
gh release create v<version> \
    build/distributions/movebank-mirror-api-boot-<version>.tar \
    build/distributions/movebank-mirror-api-boot-<version>.zip \
    --title "v<version>" \
    --notes "$(cat <<'EOF'
movebank-mirror-api <version>.

A read-only HTTP server that replays the Movebank REST API on top of
a local on-disk mirror produced by movebank-mirror.

Verified compatible with the R `move` and `move2` packages on this
release: 4/4 + 4/4 against the regression suite at
`compatibility/move-r/verify.R` and `compatibility/move2-r/verify.R`.

Install:

  tar -xf movebank-mirror-api-boot-<version>.tar
  ./movebank-mirror-api-boot-<version>/bin/movebank-mirror-api --help

Requires JDK 21. Pair with movebank-mirror to populate the mirror
directory the server reads from. See README.md and BUG_COMPATIBILITY.md
for known limitations and the verified live-API quirks.
EOF
)"
```

### 2.10 Verify the release is consumable

Pull the release archive on a clean machine (or wipe local checkouts
first):

```bash
gh release download v<version> -p '*.tar'
tar -xf movebank-mirror-api-boot-<version>.tar
./movebank-mirror-api-boot-<version>/bin/movebank-mirror-api --help
```

If `--help` works against the downloaded archive, the release is
consumable by anyone.

---

## Part 3. Troubleshooting

### Clean build fails resolving `movebank-mirror:<version>`

The runtime dependency isn't on Maven Central yet, or you commented
out the wrong toggle. Re-check §2.1 and §2.2.

If you're mid-release and the dependency really isn't there, publish
`movebank-mirror:<version>` first (its own `PUBLISHING.md` walks the
Maven Central process) and wait until `repo1.maven.org` returns
HTTP 200 for the POM before rebuilding here.

### Compatibility scripts time out / hang

Symptom is usually one of:

- **The bootRun process isn't reachable from the container.** Default
  Spring Boot bind is `127.0.0.1`. Use `-b 0.0.0.0` in the bootRun
  args (as shown in §2.5) and `--add-host=host.docker.internal:host-gateway`
  on the docker run, otherwise the container can't reach the host's
  :8080.
- **The mirror directory is missing or empty.** verify.R defaults to
  study 2911040 (Galapagos Albatrosses) — must be present in the
  mirror. Populate with `movebank-mirror metadata --study 2911040 -d
  /tmp/movebank-mirror` followed by an event-data pass.

### `bootDistTar` produces the wrong filename

Filename is `<archivesName>-boot-<version>.tar` where `archivesName`
is set in `build.gradle` (`base { archivesName = 'movebank-mirror-api' }`).
If it's wrong, the `archivesName` was changed without updating §2.6
above. Pick one and stick with it.

### `gh release create` fails with permissions error

`gh auth login` is stale. Re-run, pick the right GitHub account,
make sure the auth has `repo` scope.

### Released archive launcher fails on a clean machine

Most common cause: JDK 21 not installed on the target machine.
The launcher script (`bin/movebank-mirror-api`) is a plain shell
script that calls `java -jar …`; it relies on `java` being on PATH
and pointing at JDK 21+.

---

## Part 4. Per-release checklist

```
[ ] decided what's changing
[ ] both Maven Central deps (movebank-mirror, movebank-api-client)
    are at the right versions and HTTP 200 on repo1.maven.org
[ ] gradle.properties: localMovebankApiClient + localMovebankMirror
    both commented out
[ ] build.gradle: version bumped
[ ] ./gradlew clean build — pass (incl. DirectReadIntegrationTest)
[ ] R-client regression:
      [ ] compatibility/move-r/verify.R    → 4/4
      [ ] compatibility/move2-r/verify.R   → 4/4
[ ] ./gradlew bootDistTar bootDistZip
[ ] unpacked tarball: bin/movebank-mirror-api --help works
[ ] git tag -a v<version> -m '…' && git push origin v<version>
[ ] gh release create v<version> build/distributions/*-boot-*.{tar,zip} \
        --title '…' --notes '…'
[ ] gh release download v<version> + smoke-test on a clean directory
[ ] (optional) update the README badge / link / etc.
```
