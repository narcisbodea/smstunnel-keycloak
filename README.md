# SMSTunnel SMS code for Keycloak

A Keycloak extension that adds a sign-in step: after the password, **[SMSTunnel](https://smstunnel.io)** texts a one-time code to the user's phone, from **your own Android phone** and **your own number**, and the user types it in.

Your Keycloak hands each code to your SMSTunnel account, and your phone sends it from its own SIM, so users get it from a number they know. No SMS provider contract: the phone that sends is yours, and you can stop it at any time by deleting the API key in your dashboard.

The sending phone needs the SMSTunnel app from Google Play:

<a href="https://play.google.com/store/apps/details?id=io.smstunnel.app"><img src="https://smstunnel.io/google-play-badge.png" alt="Get it on Google Play" height="56"></a>

What it adds to Keycloak:

- **SMSTunnel SMS code**, an authentication step: texts a 4 to 10 digit code (6 by default) to the number in a user attribute (`phoneNumber` by default), asks for it, limits wrong codes and new codes, and marks the number checked (`phoneNumberVerified`) once a code comes back.
- **SMSTunnel: add a phone number**, a required action: asks a user without a number for one, texts a code to it, and saves it only when the code comes back. Applications can also start it to let a signed-in user change the number.
- Pages and texts in **English and Romanian**, in Keycloak's default `keycloak.v2` theme and the older `keycloak` theme.

## Requirements

- Keycloak 26. Tested end to end on 26.8.1 (the current release); it builds and passes its unit tests against 26.0, 26.4 and 26.6.
- An SMSTunnel account with an Android phone linked to it.
- One file to install: the extension jar (it bundles the [SMSTunnel Java client](https://github.com/narcisbodea/smstunnel-java), so nothing else goes into `providers/`).

## Get your key

1. Install the [SMSTunnel app from Google Play](https://play.google.com/store/apps/details?id=io.smstunnel.app) on the Android phone that will send the codes, and sign in. Sign in at [smstunnel.io](https://smstunnel.io) on your computer with the same account: the phone appears under **Phones** (you can also link it with the QR code from **Phones → Add phone**).
2. In your [SMSTunnel dashboard](https://dashboard.smstunnel.io/apps), open **Apps** → **Add an app**.
3. Create the key and copy it (it is shown once). It can send texts and look up whether they went out; that is all this extension needs.

## Install

Download `smstunnel-keycloak-1.0.0.jar` from the [GitHub releases](https://github.com/narcisbodea/smstunnel-keycloak/releases) (or from Maven Central: `io.github.narcisbodea:smstunnel-keycloak:1.0.0`), then:

```bash
cp smstunnel-keycloak-1.0.0.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build            # then start Keycloak as usual
```

In a container image:

```dockerfile
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
COPY smstunnel-keycloak-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

At start, Keycloak logs that `smstunnel-sms-code` and `smstunnel-phone-number` implement an "internal SPI". It says that for every custom sign-in step: Keycloak may change these interfaces in a later major version, which is why each Keycloak update should be followed by a test sign-in.

## Set it up in a realm

All in the admin console, in your realm.

1. **Let admins see and set the number.** Realm settings → **User profile** → **Create attribute**: name `phoneNumber`, display name `Phone number`. Under **Permission**, *Who can edit*: **Admin** only; *Who can view*: **User** and **Admin**. (If users could edit it in the account console, they could change it without a code.) Keycloak's own `phone` client scope already puts `phoneNumber` in tokens as `phone_number`.
2. **Add the step.** Authentication → **Flows** → `browser` → **Action** → **Duplicate**, name it `browser with SMSTunnel`. In its `... forms` sub-flow, click **+** → **Add step** → **SMSTunnel SMS code**, set it to **Required**, and drag it under *Username Password Form* if needed.
3. **Settings of the step.** Click the step's gear icon, give the settings a name, and fill in at least the **API key**. Save.
4. **Let users without a number add one.** Authentication → **Required actions** → **SMSTunnel: add a phone number** → **Enabled** on.
5. **Use the flow.** Back in the flow: **Action** → **Bind flow** → **Browser flow** → **Save**.
6. **Recommended:** Realm settings → **Security defenses** → **Brute force detection**: **Lockout temporarily**. Wrong codes then count like wrong passwords.

Sign in with a test user to check it: after the password, the user gets a text and the page asks for the code.

The test realm in [`e2e/realm/smstunnel-test-realm.json`](e2e/realm/smstunnel-test-realm.json) is a complete example of the setup above, importable with `kc.sh import` or `--import-realm`.

### Settings

| Setting | Default | |
| --- | --- | --- |
| **API key** | | Your SMSTunnel key, or a vault reference such as `${vault.smstunnel_api_key}` (below). |
| **SMSTunnel address** | `https://smstunnel.io` | Change it only for a self-hosted SMSTunnel. |
| **Default country code** | | For numbers saved without `+` or `00`, such as `+40`. Without it, users must type their number with `+` and the country code. |
| **Phone number attribute** | `phoneNumber` | The user attribute with the number. `<attribute>Verified` is set to `true` after a right code. |
| **Code length** | `6` | 4 to 10 digits. |
| **Code lifetime (seconds)** | `300` | 30 to 3600. |
| **Wrong codes allowed** | `5` | Per sign-in. After the last one, the sign-in starts over at the password. |
| **Texts per sign-in** | `3` | The first code and the new ones the user asks for. |
| **Wait before a new code (seconds)** | `30` | |
| **Text of the SMS** | | Empty: the built-in text in the user's language (below). Placeholders: `{code}`, `{realm}`, `{ttl}` (minutes). |
| **Phone ID (optional)** | | Send through one particular phone of your account (its ID is under **Phones** in the dashboard). Empty: SMSTunnel picks one of yours. |

A value out of range falls back to the nearest allowed one, with a warning in Keycloak's log. The phone-number step uses the settings of the realm's SMS code step (the one in the browser flow first).

### Keep the key in Keycloak's vault

Instead of the key itself, the **API key** setting can name a secret in Keycloak's vault, so the key is not stored in Keycloak's database. With the file vault:

```bash
kc.sh start --vault=file --vault-dir=/opt/keycloak/vault ...
# the file is named <realm>_<secret name>, with each "_" inside the names doubled:
printf '%s' 'sk_live_...' > /opt/keycloak/vault/myrealm_smstunnel__api__key
```

and set **API key** to `${vault.smstunnel_api_key}`. A Kubernetes or OpenShift secret mounted as that folder, and the Java keystore vault (`--vault=keystore`), work the same way.

## How it works for users

- **Sign-in**: password, then a page that says which number the code went to (its last 4 digits), with **Sign in** and **Send a new code**. Reloading the page does not send another text.
- **Wrong code**: the page says so and how many tries are left. After the last one, the sign-in starts over at the password page with "Too many wrong codes. Sign in again."
- **New code**: after the wait, a new code replaces the old one, up to *Texts per sign-in*.
- **No number yet**: right after the password, the user adds a number, gets a code on it, and enters it; the number is saved only then (with `phoneNumberVerified=true`), and the sign-in goes on. If the **SMSTunnel: add a phone number** action is not enabled, Keycloak stops such sign-ins ("credential setup required") instead of letting them in without a code.
- **Changing the number** from your application: send the user to the authorization endpoint with `kc_action=smstunnel-phone-number`. Keycloak asks for a new sign-in first (with a code to the current number) when the last one is older than the action's *Maximum Age of Authentication* (Authentication → Required actions → the action's settings; 5 minutes by default), then the user enters and confirms the new number; your redirect URI gets `kc_action_status=success` (or `cancelled`).
- **Offline phone**: the code waits on your SMSTunnel account and your phone sends it as soon as it is back online; the page tells users to ask for a new code if nothing arrives within a minute.

## Languages and texts

English and Romanian are built in, for the pages and for the SMS. Keycloak shows Romanian when the realm offers it (Realm settings → **Localization** → **Internationalization** on, with Română among the supported languages) and the user picks it, or the browser or the application (`ui_locales=ro`) asks for it.

Change any text, per language, in Realm settings → **Localization** → **Realm overrides**, with the keys from [`messages_en.properties`](src/main/resources/theme-resources/messages/messages_en.properties). The SMS itself is `smstunnelSmsText` (sign-in) and `smstunnelPhoneSmsText` (checking a new number), with the placeholders `{code}`, `{realm}` and `{ttl}`.

- Keep `{code}` as a word of its own: SMSTunnel accepts a sign-in text only when the code stands alone (`Code: 482913` works, `Code482913` doesn't). A text that breaks this, or is longer than 320 characters, is replaced by the built-in English one, with a warning in the log.
- Leave diacritics and emoji out of the SMS: one such letter turns it into the UCS-2 alphabet (70 characters per SMS instead of 160), so the text splits in two. The built-in Romanian SMS is written without diacritics for this reason; the pages use them.

## Errors

What users see, and what the Keycloak log says (it names the realm and the last digits of the number; never the number, the code or the key):

| Situation | Users see | Log |
| --- | --- | --- |
| No API key, a key SMSTunnel refuses (401/403), or a setting that is not valid | "Sign-in codes by text message are not set up correctly. Contact your administrator." | `ERROR` with the reason |
| SMSTunnel refuses the number (400) | "The phone number on your account cannot receive sign-in codes." (in the phone step: "This number cannot receive sign-in codes. Use another mobile number.") | `WARN` |
| Too many texts in a short time (429) | "Too many codes were sent in a short time. Wait a minute, then ask for a new code." | `WARN` |
| SMSTunnel unreachable, a timeout, a 5xx | "The code could not be sent right now. Try again in a moment.", with **Send a new code** | `WARN` |
| The sending phone is offline | the usual page; the code goes out when the phone is back | `WARN` |

Wrong and expired codes are Keycloak `LOGIN_ERROR` events (`invalid_user_credentials`, `expired_code`) and count for brute force detection. A number saved by the phone step is an `UPDATE_PROFILE` event with the attribute name and the last digits.

## Security notes

- Codes come from `SecureRandom`; the authentication session keeps only a salted SHA-256 hash, the expiry and the counters.
- A user without a number can add one after the password, as with Keycloak's own one-time-password setup. To allow only numbers you set yourself (import, LDAP mapping, admin console), leave the required action disabled: such users then cannot sign in until they have a number.
- Keep the attribute admin-editable only (step 1 above), and turn on brute force detection.
- Each sign-in sends at most *Texts per sign-in* codes, after the wait, and SMSTunnel itself limits each key to 30 texts a minute, refuses short and premium-rate numbers, and has a daily allowance per plan ([pricing](https://smstunnel.io/pricing/)).
- The key can stay in Keycloak's vault. The client never follows redirects, so the key is only ever sent to the configured SMSTunnel address.

## Build it with an AI assistant

Paste this into Claude, ChatGPT, Cursor or Copilot, and replace the part in brackets. Never paste your real API key into a prompt.

```text
Help me add sign-in codes by SMS to my Keycloak with SMSTunnel. SMSTunnel sends each text from my own Android phone, from my own number: the phone runs the SMSTunnel app (Google Play) and is linked to my SMSTunnel account.

The extension: smstunnel-keycloak 1.0.0 (https://github.com/narcisbodea/smstunnel-keycloak), one jar for Keycloak 26. Install: copy smstunnel-keycloak-1.0.0.jar into /opt/keycloak/providers/ and run kc.sh build (in a Dockerfile: COPY, then RUN /opt/keycloak/bin/kc.sh build).
It adds the authentication step "SMSTunnel SMS code" (provider id smstunnel-sms-code) and the required action "SMSTunnel: add a phone number" (smstunnel-phone-number). Setup: user profile attribute phoneNumber (edit: admin only); duplicate the browser flow, add the step as Required after Username Password Form, set its API key (or ${vault.smstunnel_api_key}) and default country code; enable the required action; bind the flow as the browser flow; turn on brute force detection.
Step settings (config keys): apiKey, baseUrl (https://smstunnel.io), defaultCountryCode, phoneAttribute (phoneNumber), codeLength (6, 4-10), codeTtl (300 s), maxAttempts (5), maxSends (3), resendAfter (30 s), smsText ({code}, {realm}, {ttl}), deviceId. Realm import JSON: authenticatorConfig with these keys, and requiredActions with providerId smstunnel-phone-number.

API facts behind it: the step calls POST https://smstunnel.io/api/v1/sms/send-2fa with the header X-API-Key and {"to", "message", "defaultCountryCode"?, "deviceId"?}, one recipient per call. The text must contain the code as a number of its own, 4 to 10 digits, at most 320 characters. defaultCountryCode (e.g. "+40") completes numbers saved without + or 00. SMSTunnel allows 30 texts a minute per key. Errors: 401 bad key, 403 missing permission or plan (with a code such as PERMISSION_REQUIRED), 429 too many (Retry-After), 5xx server. If the phone is offline, the code waits and goes out when it is back.

What I want: [describe your Keycloak setup, which users should get codes, and when]

Rules:
- Keep the API key in Keycloak's vault or the step's settings; never in source code, a repository, realm exports you share, or client-side code.
- Numbers in international format (+14155550123); make the phone attribute admin-editable only.
- Handle the errors above, and only send codes to people who are signing in.
```

## Development

```bash
mvn verify                       # unit tests and the provider jar in target/ (Java 17+)
mvn verify -Dkeycloak.version=26.4.7
```

No Java on your machine? `docker run --rm -v "$PWD":/work -w /work maven:3.9-eclipse-temurin-21 mvn verify`. The build needs `io.github.narcisbodea:smstunnel-java` from Maven Central (or `mvn install` of that repository first).

The end-to-end test ([`e2e/`](e2e/)) starts Keycloak with the jar and the test realm in Docker, and a scripted browser signs in: username and password, then the code the SMSTunnel **test** server sent through its simulated phone, read from that server's database. It covers the code sign-in, a wrong code, too many wrong codes, a new code, adding and confirming a number, Romanian, the older theme, changing the number from an application, and a realm where the phone step is off. It never calls production.

```bash
mvn -DskipTests package
export SMSTUNNEL_BASE_URL=http://host.docker.internal:3141        # the test server, as seen from a container
export SMSTUNNEL_VAULT_DIR=/path/to/vault                         # holds smstunnel-test_smstunnel__api__key, the test key
export SMSTUNNEL_E2E_MONGO_URL="mongodb://host.docker.internal:27018/<database>?directConnection=true"
docker compose -f e2e/docker-compose.yml run --rm e2e
docker compose -f e2e/docker-compose.yml down -v
```

## Links

- [SMSTunnel](https://smstunnel.io) · [API documentation](https://smstunnel.io/docs/) · [Dashboard: Apps](https://dashboard.smstunnel.io/apps)
- [SMSTunnel for Java](https://github.com/narcisbodea/smstunnel-java) (the client inside this extension)
- [Changelog](CHANGELOG.md) · [Publishing](PUBLISHING.md)

This extension is not affiliated with, endorsed by, or sponsored by the Keycloak project, the Cloud Native Computing Foundation or Red Hat. Keycloak is a trademark of its respective owners.

## License

MIT, © NARBOWEB SRL. See [LICENSE](LICENSE).
