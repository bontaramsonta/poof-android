# One bearer token per Owner, checked in the Lambda

A second person uses the app on the same control plane. With one shared token, the one-live-phone-Exit cap would block whichever phone connected second, either phone could `DELETE` the other's Exit, and one person's access could not be revoked without the other's.

Each Owner has a token stored as an SSM SecureString at `/poof/control-plane/tokens/<owner>`. On cold start the Lambda loads them all with `GetParametersByPath`, and the owner is the path suffix. A request's bearer is compared in constant time against each. The Lambda tags phone Exits `poof:owner=<owner>`, counts the cap per Owner, and refuses `GET` and `DELETE` on another Owner's Exit. `token.sh <owner>` creates a token, and `token.sh <owner> --rotate` replaces it. Both force a cold start. Decided in #27 and #29; the rules are spec §4.2 and §4.3.

The Owner check lives in code, not IAM. One role serves every token, so IAM sees one principal. IAM still requires the `poof:owner` tag at launch, and terminate stays limited to `poof:client=android`, so a leaked token still cannot touch CLI Exits.

The app does not change. Both Owners install the same APK and paste their own token. Owner names match poof's ([ADR-0007](https://github.com/bontaramsonta/poof/blob/main/docs/adr/0007-owner-tag-by-convention.md)), so a phone Exit and a CLI Exit of the same person carry the same tag.

**Considered Options**

- One shared token (rejected: phones collide on the cap and can delete each other's Exits; no per-person revoke).
- One SecureString holding a JSON map of owner to token (rejected: each rotation rewrites every token, and a bad write locks everyone out).
- `GET /me` and a "Signed in as" screen (rejected: a wrong token already fails at `GET /countries` with 401).
- Per-owner builds with the token baked in (rejected: one APK serves both).

**Consequences**

- Migration: the value of `/poof/control-plane/token` moves to `/poof/control-plane/tokens/sourav`, and the old parameter is deleted. The owner's phone needs no re-paste.
- Adding an Owner is `token.sh <owner>` and a cold start. There is no redeploy.
- The control plane is no longer single-user, but it still has no accounts: a token is the whole identity.
