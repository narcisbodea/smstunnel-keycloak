# Publishing

Three places, in this order:

1. **GitHub release** with the jar: what most Keycloak admins download.
2. **Maven Central** (`io.github.narcisbodea:smstunnel-keycloak`): for teams that build their Keycloak image with Maven or Gradle.
3. **The extensions page on keycloak.org**: where people look for Keycloak extensions.

One GitHub release does 1 and 2 (`.github/workflows/release.yml`); 3 is a one-time request to the Keycloak website.

**Before anything:** publish [`smstunnel-java`](https://github.com/narcisbodea/smstunnel-java) 1.0.0 to Maven Central first. This build downloads it from there; until it is on Central, CI here fails at "Could not resolve io.github.narcisbodea:smstunnel-java:1.0.0".

## 1. GitHub repository (once)

1. Create the public repository `narcisbodea/smstunnel-keycloak` and push this folder to `main`.
2. **Settings → Environments → New environment** `maven-central`, set up exactly as for smstunnel-java (its `PUBLISHING.md`, step 3): **Required reviewers** yourself, tag rule `v*`, and the four secrets `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`, `MAVEN_GPG_KEY`, `MAVEN_GPG_PASSPHRASE`. The same Central token and the same GPG key work for both repositories (same namespace).
3. **Actions**: **CI** runs the unit tests against Keycloak 26.8.1, 26.6.4, 26.4.7 and 26.0.8, checks the jar's contents, and keeps the jar as a build artifact.
4. Optional, under **About** (the gear on the repository page): description "SMSTunnel SMS code: sign-in codes by SMS for Keycloak, from your own Android phone", website `https://smstunnel.io`, topics `keycloak`, `keycloak-extension`, `authenticator`, `sms`, `2fa`, `smstunnel`.

## 2. Release 1.0.0

1. Check that `pom.xml` and the top of `CHANGELOG.md` say `1.0.0`, and that **CI** is green.
2. **Releases → Draft a new release → Choose a tag** `v1.0.0` (**Create new tag on publish**, target `main`), title `1.0.0`, paste the changelog entry, **Publish release**.
3. **Actions → Release**:
   - the job **Attach the jar to the release** builds and tests, then adds `smstunnel-keycloak-1.0.0.jar`, its `.sha256` and a signed build provenance attestation (anyone can check it with `gh attestation verify smstunnel-keycloak-1.0.0.jar -R narcisbodea/smstunnel-keycloak`);
   - the job **Publish to Maven Central** waits for your approval of the `maven-central` deployment, then signs and uploads. Central validates in 2 to 10 minutes and publishes by itself; the files reach repo1.maven.org in 15 to 30 minutes.
4. Check the release page: the jar is attached. Try it: `docker run --rm -p 8080:8080 -v $PWD/smstunnel-keycloak-1.0.0.jar:/opt/keycloak/providers/smstunnel-keycloak.jar -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:26.8.1 start-dev`, then **Authentication → Flows → Add step** lists "SMSTunnel SMS code".

For later versions: `mvn versions:set -DnewVersion=1.0.1 -DgenerateBackupPoms=false`, a new `CHANGELOG.md` entry, commit, and a release tagged `v1.0.1`. When a new Keycloak version comes out, add it to the CI matrix, run the end-to-end test (README, Development) against it, and raise `keycloak.version` in `pom.xml`.

## 3. The extensions page on keycloak.org

How it takes new entries (checked 3 November 2026):

- The page (<https://www.keycloak.org/extensions>) says: "Note that those extensions are not vetted by the Keycloak team, and are maintained independent third parties. To add an extension, open an issue on GitHub", linking to <https://github.com/keycloak/keycloak-web/issues>. The list shows each entry's GitHub stars.
- Each entry is a file in [`extensions/`](https://github.com/keycloak/keycloak-web/tree/main/extensions) of the `keycloak-web` repository with three fields (`extension-template.json`: `name`, `description`, `website`). Recent entries came as a pull request from the extension's author adding that one file, next to an issue (for example #840 and #841, merged the same day; others took one to two weeks).
- `keycloak-web` has no contribution guide or AI policy of its own; blank issues are turned off, so the issue uses its **Enhancement Request** template (a required *Description*; *Discussion*, *Motivation* and *Details* are optional).
- Keycloak's rules for AI-assisted contributions are in the main repository: [CONTRIBUTING.md, "Use of Generative AI"](https://github.com/keycloak/keycloak/blob/main/CONTRIBUTING.md) **allows** AI-assisted contributions when the contributor understands every change, answers reviewers personally (AI-drafted replies edited and checked), can make requested changes, **says in the pull request description that an AI agent was used**, and keeps the licensing clean (Apache 2.0 for contributed code). Its [AGENTS.md](https://github.com/keycloak/keycloak/blob/main/AGENTS.md) tells AI agents to create issues only through the repository's templates (no blank issues) and never to report security problems in public issues.

So: you (not an agent) open the issue and the pull request from your own account, once the repository is public with release 1.0.0, and say in the pull request that an AI assistant prepared the entry and that you reviewed it.

**The issue** (<https://github.com/keycloak/keycloak-web/issues/new/choose> → **Enhancement Request**), title `Add extension: SMSTunnel SMS code`:

- *Description*: `Please add "SMSTunnel SMS code" to the community extensions page. It is a Keycloak 26 authenticator that sends one-time sign-in codes by SMS from the deployer's own Android phone and number (through SMSTunnel), with a required action that adds and verifies the user's phone number, and pages in English and Romanian. MIT licensed, maintained by NARBOWEB SRL: https://github.com/narcisbodea/smstunnel-keycloak`
- *Details*: the JSON below.

**The pull request** (optional, it makes the maintainers' work a merge): fork `keycloak/keycloak-web`, add `extensions/smstunnel-sms-code.json`:

```json
{
    "name": "SMSTunnel SMS code",
    "description": "Sign-in codes by SMS from your own Android phone and number: an authenticator, a required action that adds and verifies the phone number, English and Romanian texts.",
    "website": "https://github.com/narcisbodea/smstunnel-keycloak"
}
```

commit it with a sign-off (`git commit -s -m "Add SMSTunnel SMS code extension"`), and open the pull request with the description:

```text
Closes #<the issue number>.

Adds SMSTunnel SMS code to the community extensions page, using the three-field format of extension-template.json.
Release 1.0.0 (Keycloak 26, tested on 26.8.1): https://github.com/narcisbodea/smstunnel-keycloak/releases/tag/v1.0.0

The entry was prepared with an AI assistant (Claude); I reviewed it and maintain the extension.
```

## What stays manual

Creating the GitHub repository and its environment and secrets (the Central account, token and GPG key from smstunnel-java), publishing the release, approving the Maven Central deployment, and the keycloak-web issue and pull request.
