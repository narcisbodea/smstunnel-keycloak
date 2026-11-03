# Changelog

## 1.0.0 (2026-11-03)

First release, for Keycloak 26 (tested end to end on 26.8.1; builds against 26.0 to 26.8).

- **SMSTunnel SMS code** (`smstunnel-sms-code`): an authentication step that texts a 4 to 10 digit code through SMSTunnel's sign-in code route and asks for it. Per-realm settings: API key (or a vault reference), SMSTunnel address, default country code, phone attribute, code length, lifetime, wrong codes allowed, texts per sign-in, wait before a new code, text of the SMS and phone ID.
- **SMSTunnel: add a phone number** (`smstunnel-phone-number`): a required action that checks a new number with a code before saving it (with `<attribute>Verified=true`). It runs for users without a number at sign-in, and from applications (`kc_action`) to change the number.
- When that action is not enabled, users without a number are refused instead of signed in without a code.
- Codes kept only as salted hashes; wrong codes are failed sign-ins for brute force detection.
- Pages and SMS texts in English and Romanian; the `keycloak.v2` and `keycloak` login themes.
