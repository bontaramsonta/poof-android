# poof-android spec

The build-ready spec for poof-android: a sideloaded Android app plus a thin AWS control plane that gives the phone poof's promise. Pick a Country, a fresh Exit appears, the phone's traffic comes out of it, and on disconnect it's gone.

Every decision here traces to a closed ticket on the [wayfinder map](https://github.com/bontaramsonta/poof-android/issues/1). Ticket numbers in brackets (e.g. [#9]) link the source. Domain terms come from [poof's CONTEXT.md](https://github.com/bontaramsonta/poof/blob/main/CONTEXT.md) and [this repo's CONTEXT.md](../CONTEXT.md).

## 1. Scope

**v1 does:** Country picker, Connect, Disconnect (which destroys the Exit), and live status (Country, Exit IP, uptime, bytes down/up, last handshake).

**Frame:**
- **System mode only**, through Android `VpnService`. No Proxy mode.
- **Single user** (the owner). No accounts.
- **The phone holds no AWS credentials.** A thin control plane launches and terminates Exits. The phone authenticates with a long random bearer token.
- **One live phone Exit at a time**, as a cost guard. CLI Exits do not count.
- **Phone Exits and CLI Exits are interchangeable**: same `poof=1` tag, same user-data, same Dead-man's switch, built from the same exported poof packages. The CLI stays direct to EC2.
- **Sideloaded APK**, native Kotlin + Jetpack Compose, built locally with Gradle. No CI.

**Out of scope:** the CLI using the control plane; multi-user or SSO; Google Play; always-on VPN and the system kill switch (they would relaunch Exits by themselves); Quick Settings tile and widget; per-app routing; saved Countries; Proxy mode; QR token handoff; CI and release signing on GitHub Actions; IPv6 through the Exit (the upgrade path is recorded on the map).

## 2. Architecture

```
 Phone (poof-android)                     AWS, ap-south-1                    AWS, 12 regions
 ┌──────────────────────────┐  HTTPS      ┌─────────────────────────┐  EC2   ┌──────────────────┐
 │ Compose UI               │  Bearer     │ Lambda (Go, arm64)      │  API   │ Exit (t4g.nano)  │
 │ Session store (Keystore) ├────────────►│ function URL, auth NONE ├───────►│ poof user-data   │
 │ PoofVpnService (FGS)     │             │ reserved concurrency 1  │        │ Dead-man's switch│
 │  └ GoBackend tunnel ─────┼─────────────┼─────── WireGuard UDP 51820 ──────►│                  │
 └──────────────────────────┘             │ SSM: token  Logs: 7 d   │        └──────────────────┘
                                          └─────────────────────────┘
```

- **Repo layout:** `android/` (the app), `control-plane/` (Go module, `template.yaml`, `token.sh`), `docs/`.
- **Stateless control plane.** EC2 tags are the only server-side record. The phone holds all Session data and addresses an Exit by `{region, instanceId}` [#7].
- **The Session lives on the phone** as its stored Session record [#9].

## 3. Required poof changes

These land in poof before the control plane is built. poof-android pins the resulting poof version [#5, #12, #19].

1. **Export the packages.** `internal/provision` and `internal/wgkey` become public packages in the poof module (e.g. `github.com/bontaramsonta/poof/exit` and `.../wgkey`). The CLI adopts them too.
2. **Launch takes extra instance tags.** The caller MUST be able to add `poof:client=android` next to `poof=1` and `Name`. All instance tags MUST be set in the `RunInstances` request (`TagSpecifications`), never by a later `CreateTags`.
3. **One persistent security group per region.** Fixed name, tagged `poof=1` at creation, inbound UDP 51820 only. Launch ensures it: look it up by name, create it if missing, re-add the rule if it was removed. It is never deleted. This replaces the per-launch group, which leaked groups on every teardown.
4. **Launch passes no key pair and no instance profile.** True today; the IAM policy depends on it.
5. **Drop `poof nuke`.** The Dead-man's switch is the only backstop for orphaned Exits.
6. **Swap before `dnf`.** Merge the 1 GB swap file from branch `phone-test-mode` (30bebf1) to `main`. Without it, `dnf` is intermittently OOM-killed on the 512 MB nano and the Exit never answers.
7. **Tag a release** for poof-android to pin.

**Cross-client invariants** (version skew between the repos is tolerated as long as these hold): the `poof=1` tag, the security-group name, shutdown-means-terminate, the 5 min Dead-man's switch threshold.

## 4. Control plane

### 4.1 Hosting

- **One Go Lambda** on `provided.al2023`, arm64, in **`ap-south-1`**, in the same AWS account as the CLI [#3, #11].
- **Function URL with `AuthType: NONE`.** The bearer token is checked in code. API Gateway is ruled out by its 30 s request cap [#3].
- **`ReservedConcurrentExecutions: 1`.** This guards the one-Exit cap against races and limits how fast a leaked token can be abused. A concurrent request gets 429 [#7].
- **The 12 Countries**, all in default-enabled regions: usa `us-east-1`, ireland `eu-west-1`, germany `eu-central-1`, britain `eu-west-2`, france `eu-west-3`, japan `ap-northeast-1`, korea `ap-northeast-2`, singapore `ap-southeast-1`, australia `ap-southeast-2`, india `ap-south-1`, canada `ca-central-1`, brazil `sa-east-1`. The map lives in poof's exported package.
- **Cost:** about $0 at this traffic.

### 4.2 Auth

Every request carries `Authorization: Bearer <token>`. The Lambda reads the token from SSM SecureString `/poof/control-plane/token` once per cold start and compares in constant time. A missing or wrong token gets **401** [#11].

### 4.3 API [#7]

| Endpoint | Does | Returns |
|---|---|---|
| `GET /countries` | the Country list from poof's exported map | the 12 Countries |
| `POST /exits` `{country, clientPublicKey}` | cap check → ensure the region's security group → generate the server keypair → render user-data → `RunInstances` → wait for a public IP | `201 {instanceId, region, publicIp, serverPublicKey}`; `409 {country, region, instanceId, publicIp}` of the existing phone Exit |
| `GET /exits/{region}/{instanceId}` | `DescribeInstances` | `{state}` |
| `DELETE /exits/{region}/{instanceId}` | `TerminateInstances` | `204`; refused unless the instance is tagged `poof:client=android` |

- **Cap:** `POST` counts `pending` or `running` instances tagged `poof:client=android` across the 12 regions. One or more → 409. CLI Exits are not counted and run side by side.
- **Synchronous launch.** `POST` returns at the public IP (~7–8 s measured). The phone waits out the ~47 s boot itself. A failure before the public IP makes the Lambda terminate the instance, as poof's `Launch` does.
- **Key custody** [#6]: the phone sends only its client public key. The Lambda generates the server keypair with poof's `wgkey`, renders user-data with `RenderUserData` (server private key, client public key, tunnel IPs `10.66.0.1`/`10.66.0.2`, port 51820, `IdleShutdownMin = 5`), and returns only the server public key. Only public keys cross the network.
- **A leaked token cannot touch CLI Exits**: `DELETE` and the IAM role both act only on `poof:client=android`.

### 4.4 IAM role [#19, #20]

Least privilege. The policy goes into `template.yaml` as an inline policy (`!Sub` fills `${AWS::AccountId}`).

- **Region guard:** a Deny on every EC2 call outside the 12 regions.
- **Launch:** `t4g.nano` only, from Amazon-owned AMIs only. The request MUST tag `poof=1` and `poof:client=android`; allowed tag keys are `poof`, `poof:client`, `Name`. The security group must carry `poof=1`. No key pair, no `iam:PassRole`.
- **Tags:** only during `RunInstances` or `CreateSecurityGroup`. Existing resources cannot be retagged, so a CLI Exit can never become terminable.
- **Security group ensure:** create only with `poof=1`; add ingress only to groups tagged `poof=1`; no delete.
- **Terminate:** only `poof:client=android`.
- **Describe:** `DescribeInstances`, `DescribeSecurityGroups`.
- **SSM:** the public AL2023 arm64 AMI parameter, and the token parameter. The token uses the `aws/ssm` managed key, so no KMS grant.
- **Logs:** `logs:CreateLogStream` and `logs:PutLogEvents` on the function's own log group only.
- **Excluded:** `GetConsoleOutput`, `DeleteSecurityGroup`.

<details><summary>EC2 and SSM policy JSON</summary>

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "OnlyCountryRegions",
      "Effect": "Deny",
      "Action": "ec2:*",
      "Resource": "*",
      "Condition": {
        "StringNotEquals": {
          "aws:RequestedRegion": [
            "us-east-1", "eu-west-1", "eu-central-1", "eu-west-2",
            "eu-west-3", "ap-northeast-1", "ap-northeast-2", "ap-southeast-1",
            "ap-southeast-2", "ap-south-1", "ca-central-1", "sa-east-1"
          ]
        }
      }
    },
    {
      "Sid": "LaunchPhoneExit",
      "Effect": "Allow",
      "Action": "ec2:RunInstances",
      "Resource": "arn:aws:ec2:*:*:instance/*",
      "Condition": {
        "StringEquals": {
          "ec2:InstanceType": "t4g.nano",
          "aws:RequestTag/poof": "1",
          "aws:RequestTag/poof:client": "android"
        },
        "ForAllValues:StringEquals": {
          "aws:TagKeys": ["poof", "poof:client", "Name"]
        }
      }
    },
    {
      "Sid": "LaunchFromAmazonAMI",
      "Effect": "Allow",
      "Action": "ec2:RunInstances",
      "Resource": "arn:aws:ec2:*::image/*",
      "Condition": { "StringEquals": { "ec2:Owner": "amazon" } }
    },
    {
      "Sid": "LaunchIntoPoofGroup",
      "Effect": "Allow",
      "Action": "ec2:RunInstances",
      "Resource": "arn:aws:ec2:*:*:security-group/*",
      "Condition": { "StringEquals": { "ec2:ResourceTag/poof": "1" } }
    },
    {
      "Sid": "LaunchNetworkAndDisk",
      "Effect": "Allow",
      "Action": "ec2:RunInstances",
      "Resource": [
        "arn:aws:ec2:*:*:subnet/*",
        "arn:aws:ec2:*:*:network-interface/*",
        "arn:aws:ec2:*:*:volume/*"
      ]
    },
    {
      "Sid": "TagOnlyAtCreate",
      "Effect": "Allow",
      "Action": "ec2:CreateTags",
      "Resource": [
        "arn:aws:ec2:*:*:instance/*",
        "arn:aws:ec2:*:*:security-group/*"
      ],
      "Condition": {
        "StringEquals": {
          "ec2:CreateAction": ["RunInstances", "CreateSecurityGroup"]
        }
      }
    },
    {
      "Sid": "EnsureGroupCreate",
      "Effect": "Allow",
      "Action": "ec2:CreateSecurityGroup",
      "Resource": "arn:aws:ec2:*:*:security-group/*",
      "Condition": { "StringEquals": { "aws:RequestTag/poof": "1" } }
    },
    {
      "Sid": "EnsureGroupInDefaultVpc",
      "Effect": "Allow",
      "Action": "ec2:CreateSecurityGroup",
      "Resource": "arn:aws:ec2:*:*:vpc/*"
    },
    {
      "Sid": "EnsureGroupRule",
      "Effect": "Allow",
      "Action": "ec2:AuthorizeSecurityGroupIngress",
      "Resource": "arn:aws:ec2:*:*:security-group/*",
      "Condition": { "StringEquals": { "ec2:ResourceTag/poof": "1" } }
    },
    {
      "Sid": "TerminatePhoneExitsOnly",
      "Effect": "Allow",
      "Action": "ec2:TerminateInstances",
      "Resource": "arn:aws:ec2:*:*:instance/*",
      "Condition": { "StringEquals": { "ec2:ResourceTag/poof:client": "android" } }
    },
    {
      "Sid": "Describe",
      "Effect": "Allow",
      "Action": ["ec2:DescribeInstances", "ec2:DescribeSecurityGroups"],
      "Resource": "*"
    },
    {
      "Sid": "ResolveAL2023",
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:*::parameter/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    },
    {
      "Sid": "ReadToken",
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:ap-south-1:${AWS::AccountId}:parameter/poof/control-plane/token"
    }
  ]
}
```

</details>

### 4.5 Logging [#20]

- **One structured JSON line per request**, via `log/slog`'s JSON handler. Fields: route, status, Country/region, instance ID, error text, and per-step timings (security-group ensure, `RunInstances`, wait for public IP, total).
- **MUST NOT log** the token, any key (the client public key included), user-data, or the caller's source IP.
- **Retention: 7 days.** `template.yaml` declares the log group `/aws/lambda/<function>` with `RetentionInDays: 7`.

### 4.6 Deploy and token [#11]

- **AWS SAM.** `control-plane/template.yaml` holds the function (`FunctionUrlConfig: {AuthType: NONE}`, `ReservedConcurrentExecutions: 1`), its role (§4.4) and its log group (§4.5). `sam build && sam deploy` compiles and deploys. CloudFormation holds the state; one command tears it down.
- **The deploying profile** needs CloudFormation, Lambda, IAM and SSM permissions.
- **`control-plane/token.sh`:** generates the token with `openssl rand -base64 32`, stores it as the SSM SecureString, and prints it. It can type it into the app's token field with `adb shell input text`.
- **Rotation:** `token.sh --rotate` overwrites the parameter and touches the function config to force a cold start. The old token dies at once; paste the new one on the phone.

## 5. The Exit

Unchanged from poof, rendered from the same template [#5, #13].

- `t4g.nano`, Amazon Linux 2023 arm64 (AMI resolved from SSM), shutdown-means-terminate, the region's persistent security group.
- User-data: 1 GB swap, `dnf install wireguard-tools nftables`, `wg0` at `10.66.0.1/24` on UDP 51820 with exactly one peer (the phone's public key, `10.66.0.2/32`), IPv4 forwarding and nftables masquerade, the Dead-man's switch.
- **Dead-man's switch:** 5 min of handshake silence, checked every minute, so it fires 300–420 s after the last handshake. Before the first handshake, silence counts from boot, so a launch has ~5 min to connect (boot takes ~45 s with swap).
- **Effective reconnect window:** ~3–6 min after the phone goes quiet, since a connected phone handshakes every ~120–126 s. Phone and CLI share it.
- **Sleep headroom:** the worst measured gap on a sleeping phone was ~198 s against the 300 s threshold [#12]. If an Exit ever dies under a sleeping phone, start with [the measurement doc](measurements/2026-10-04-phone-handshake-gaps.md).

## 6. Android app

### 6.1 Stack [#8, #14]

- **Native Kotlin + Jetpack Compose** (Material 3, Navigation 3, Gradle version catalog) in `android/`. Application ID `dev.bontaramsonta.poof`, minSdk 26, targetSdk and compileSdk 36, AGP 9. Already scaffolded (1154582).
- **Tunnel:** `com.wireguard.android:tunnel` (`GoBackend`). It ships prebuilt native libraries; no NDK.
- **HTTP:** OkHttp + kotlinx.serialization.
- **Secrets:** an Android Keystore AES-GCM key encrypts the bearer token and the Session record. `EncryptedSharedPreferences` is deprecated and not used.
- **Build:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`. Sideload over `adb`.

### 6.2 Tunnel [#17, #18]

The config is built in code with the library's `Interface`, `Peer` and `Config` builders:

| Field | Value |
|---|---|
| Interface address | `10.66.0.2/32` |
| Interface private key | the Session's client private key |
| DNS | `1.1.1.1`, `1.0.0.1` |
| Peer public key | `serverPublicKey` from `201` |
| Peer endpoint | `publicIp:51820` |
| Peer AllowedIPs | `0.0.0.0/0` |
| Persistent keepalive | 25 s |

- **Exactly one peer with a `/0` route.** GoBackend then skips `allowFamily`, and Android installs an unreachable `::/0`: IPv6 fails fast instead of leaking.
- **MUST NOT add** a v6 address, a v6 route, a v6 DNS server, a second peer or a split route. Any of these allows the v6 family, and v6 then falls through outside the tunnel.
- **MUST push a DNS server.** With none, DNS falls back to the underlying network's resolvers.
- **Private DNS:** no handling. Strict mode sends DNS over TLS to the owner's provider over the VPN network, which is not a leak. Automatic mode uses DNS over TLS against `1.1.1.1`.
- Once the tunnel is up, the app's own control-plane calls go through it.

### 6.3 Foreground service [#4, #8, #21]

- **`PoofVpnService`**, our own foreground service of type `systemExempted`, holds the `GoBackend`. The library's own `GoBackend$VpnService` has no `startForeground` and no `onRevoke`, so our service covers both.
- **It starts at the Country tap**, before `POST /exits`, so the whole connect survives the user leaving the app.
- **Stats** are polled from `getStatistics()` (rx, tx, last handshake) into a `StateFlow` for Compose. Polling runs only while the UI is visible, plus a slow check for the network-loss rule (§6.4, ending 5).
- **On `onRevoke`, the engine MUST stop** (tunnel `DOWN`) before anything else. A device left running could keep handshaking and keep the Exit alive.
- **Swiping the app from Recents is not an ending**; the service keeps running (`stopWithTask` stays false).
- **Consent:** the activity runs `VpnService.prepare()` at the first Connect. On Android 13+, `POST_NOTIFICATIONS` is requested once, right after VPN consent. A denial is tolerated and never asked again.

**Notifications:**
- **Connecting:** "Connecting to <Country>" with the current step (*Launching an Exit*, then *Waiting for it to answer*). No action.
- **Connected:** posted once at the first handshake, never updated. "Connected to <Country>", the Exit IP, a system-drawn uptime timer (`setUsesChronometer`), and a **Disconnect and destroy** action that runs the same path as the in-app button.
- **Revoked:** "Disconnected, Exit destroyed".

### 6.4 Session [#6, #9]

**A phone Session is the life of its stored Session record.** The record holds the client private key, Exit IP, server public key, instance ID, region and Country, encrypted with the Keystore key.
- It **begins** when `POST /exits` returns `201`; the app writes the record then.
- It **ends** when the record is deleted. No key outlives its Session; there is no long-lived device key.
- The tunnel is a state inside the Session. A Session can exist with its tunnel down (app killed, reboot, network out).
- **Accepted gap:** if the app dies between sending `POST` and receiving `201`, the Exit has no record. The Dead-man's switch reaps it within ~5 min, and a 409 shows it if the user connects sooner.

**Connect:** generate the client keypair → `POST /exits` → write the record → bring the tunnel up → wait for the first handshake, up to **3 min**.

**Endings:**

| # | Ending | Detected by | App does | Exit |
|---|---|---|---|---|
| 1 | Disconnect (button or notification) | tap | stop tunnel → `DELETE` → delete record | destroyed now |
| 2 | Handshake timeout at launch (3 min) | timer | `DELETE` → delete record → failure screen | destroyed now |
| 3 | VPN revoked (Settings, or another VPN app) | `onRevoke` | stop engine (MUST) → `DELETE` → delete record → "Disconnected, Exit destroyed" notification | destroyed now |
| 4 | App killed, Task Manager stop, reboot | next app open | record survives. On open, `GET` the Exit: alive → "Your <Country> Exit is still running" with **Reconnect** / **Destroy**; gone → delete record, "Your Exit expired" | the switch reaps it 3–6 min after the last handshake unless reconnected |
| 5 | Network lost, app alive | handshake age grows | keep the tunnel up; once age > ~7 min, `GET`: terminated → end Session ("Your Exit expired"); control plane unreachable → keep waiting | dies by the switch if the outage outlasts the window |
| 6 | Exit dies under a connected phone | as 5 | as 5 | already gone |

- **Not endings:** swipe from Recents; Wi-Fi ↔ cellular handoff (measured: no gap).
- **`DELETE` fails** in endings 1–3 (offline): the record is deleted anyway, and the app says "The Exit will self-destruct within ~6 minutes". The record is never kept for retries.
- **429:** the app retries with a short backoff.

### 6.5 Screens [#10, #20]

A stack of full screens. The theme follows the system dark mode (Material 3, dynamic colour on Android 12+). Prototype for reference only: branch `prototype/app-screen-flow`.

1. **Token setup** (first run): paste the token, then Save.
2. **Country picker:** a plain list of the 12 Countries, by display name ("USA", not "Usa"). Tapping a row connects at once.
3. **Connecting:** "Connecting to <Country>" with two steps, *Launching an Exit* (`POST`) and *Waiting for it to answer (<ip>)* (first handshake), plus "This usually takes about a minute." The step spinner is a fixed 18 dp square; the step markers sit in one aligned column. No Cancel.
4. **Connected:** a stats card (Exit IP, uptime, down, up, last handshake) and a full-width **Disconnect and destroy** button.
5. **Failure** (handshake timeout or launch error): "Couldn't connect to <Country>". For a timeout: "The Exit never answered. It has been terminated." Whenever an instance existed, the screen shows its **region and instance ID, copyable**, so the owner can read the console from the Mac with `aws ec2 get-console-output` soon after. Then Back.
6. **409, Exit already running:** names its Country and IP, with **Terminate it** / Cancel.
7. **Still running** (ending 4): "Your <Country> Exit is still running" with **Reconnect** / **Destroy**.
8. **Expired** (endings 4–6): "Your Exit expired", then back to the picker.

## 7. Testing [#22]

Local runs only; no CI.

- **Android, JVM unit tests (`./gradlew test`):**
  - the Session state machine, as pure Kotlin with no Android types;
  - the API client against OkHttp `MockWebServer`: 201, 409, 429 retry, 401, and the handshake-timeout → `DELETE` path;
  - the tunnel config builder: v4 `0.0.0.0/0` only, DNS `1.1.1.1` and `1.0.0.1`, no v6 route or DNS.

  No instrumented or screenshot tests.
- **Control plane, Go handler tests (`go test ./...`):** a fake EC2 behind a small interface the Lambda defines over poof's provisioner. Cases:
  - a bad or missing token gets 401;
  - the cap returns 409 with the existing Exit;
  - `DELETE` refuses an instance not tagged `poof:client=android`;
  - a failure before the public IP terminates the instance;
  - launch tags carry `poof:client=android`;
  - no token, key or user-data appears in any log line.

  `sam validate --lint` runs before every deploy. The IAM policy is proven by the first real connect.
- **Manual end-to-end on the phone:** [testing/e2e-checklist.md](testing/e2e-checklist.md). The full checklist runs before installing a build that touches the tunnel, the Session or the control plane. Steps 1–3 and 11 are the smoke test for any other build.

## 8. Build order

Each step ends with its tests green.

1. **poof changes** (in `~/p/poof`): export `exit` and `wgkey`; extra launch tags set in `RunInstances`; persistent security group with ensure; drop `nuke`; merge swap-before-dnf; adopt in the CLI. *Test:* poof's existing user-data tests (swap before dnf included), plus one `poof up` from the Mac. Tag a release.
2. **Control plane handlers** (`control-plane/`): Go module pinned to that release; token check, `GET /countries`, `POST /exits` with cap and cleanup, `GET`/`DELETE /exits/{region}/{id}`, `slog` lines. *Test:* the handler tests in §7 against the fake EC2.
3. **Control plane deploy:** `template.yaml` with the function URL, reserved concurrency 1, the §4.4 role and the 7-day log group; `token.sh` with `--rotate`. `sam validate --lint`, then `sam build && sam deploy`. *Test:* with `curl`: `GET /countries`; `POST /exits` with a throwaway public key (the first real launch proves the IAM policy); a second `POST` → 409; `DELETE` → 204; no `poof:client=android` instance left.
4. **App core** (pure Kotlin): the tunnel config builder, the Session state machine, the API client, and the Keystore-encrypted store for the token and the Session record. *Test:* the JVM unit tests in §7.
5. **Tunnel service:** `PoofVpnService` (`systemExempted`) holding `GoBackend`, VPN consent, `onRevoke` stop-then-`DELETE`, stats `StateFlow`, the two notifications and their Disconnect action, the network-loss check. *Test:* checklist steps 2–5 on the phone, with a bare debug screen.
6. **Screens:** token setup, Country picker, connecting, connected, failure (with region and instance ID), 409, still running, expired; the Reconnect/Destroy flow on open. *Test:* the full [e2e checklist](testing/e2e-checklist.md).
7. **Ship:** `./gradlew assembleDebug`, sideload, paste the token with `token.sh`. Update the README status.

## 9. Assumptions made while writing

These were not decided on the map. They are the smallest choices that let the build proceed; change them freely.

- **Install a debug build.** Release signing was dropped with CI. A single sideloaded device needs no release keystore.
- **`android:allowBackup="false"`.** The scaffold has `true`. Backed-up encrypted blobs are useless without the device-bound Keystore key, and backups go against "nothing is remembered".
- **Country display names live in the app.** `GET /countries` returns poof's lowercase names; the app maps them to display names ("USA", "Britain").
- **Lambda timeout 60 s, 128 MB.** `POST` normally takes ~8 s; the timeout bounds a stuck public-IP wait. The app's OkHttp call timeout matches it.
- **The security-group name** is whatever poof's exported package fixes in step 1.
