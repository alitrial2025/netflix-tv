# NitroFlare upload and monitoring tools

Shared infrastructure for NetflixPro TV/mobile, using Node 22+ and no dependencies.
This is an operator CLI, not a publicly accessible credential-bearing proxy.
Neither app's playback pipeline is changed.

## Setup

Run from `tools/nitroflare`. Set `NITROFLARE_USER_HASH` in secure environment
settings for uploads. Do not put it in an APK, committed file, or shell command
history. `.env.example` documents variable names; the CLI does not load dotenv.
Private state defaults to `/workspace/cloud-setup/nitroflare-state`; set
`NITROFLARE_STATE_DIR` to a durable directory outside the repository on another
host. State files contain potentially sensitive download tokens and responses.
Directories/files are created with 0700/0600 permissions; use a private parent
and enforce equivalent permissions if reusing existing files. Back up the registry.

```sh
node --test
node cli.mjs upload /absolute/path/to/authorized-video.mp4
node cli.mjs register FILE_ID
node cli.mjs status
node cli.mjs download-begin FILE_ID
# Complete the provider's CAPTCHA; set NITROFLARE_CAPTCHA securely.
node cli.mjs download-complete FILE_ID
```

IDs are hexadecimal NitroFlare file IDs. The CLI prints timing and status, not
credential-bearing URLs. Download results are saved privately in state. Upload
server addresses must be HTTPS NitroFlare hosts; the published HTTP example is
not accepted silently. Redirects are disabled to protect credentials. Any provider
endpoint changes must be reviewed explicitly. Do not disable TLS verification.

The free download flow honors the server's waiting period and requires a real
user-completed challenge response. There is no CAPTCHA bypass, Premium password
handling, or automatic throttle retry. Provider error 12 requires the documented
NitroFlare challenge page; follow provider instructions manually. A CLI alone
cannot supply an interactive CAPTCHA widget. Live challenge/link compatibility
remains unverified.

Uploads stream from disk through Node's file-backed Blob. Failed uploads are not
retried automatically because the provider may have accepted the file before a
response was lost. The original response is saved privately; if its shape is
unrecognized, inspect it and register the file ID instead of uploading again.

## Scheduled availability checks

Use a scheduler on the machine holding the state directory, for example cron:

```cron
17 3 * * * cd /workspace/netflix-tv/tools/nitroflare && /usr/bin/env node cli.mjs status >> /workspace/cloud-setup/nitroflare-status.log 2>&1
```

Ensure Node is available on cron's PATH. Checks are metadata-only, not synthetic
views/downloads. A status run returns 0 when all registered files are online,
2 for offline/unknown files, and 1 for operational/provider errors. An empty
registry makes no network calls. The latest check is stored in `last-status.json`;
cron logs preserve timing history. Configure log rotation and surface nonzero
results through your existing monitoring. The schedule is a template, not installed
or enabled by this change.

`getFileInfo` exposes no last-download timestamp. These tools cannot calculate
inactivity or guarantee preservation; keep a separate authoritative backup and
use a provider-supported retention plan. No artificial keep-alive downloads are
implemented.

Operations take an exclusive state lock. After a killed process, verify it has
stopped before removing a stale `.lock`. Registry updates use atomic renames.
Use a single local filesystem, not concurrent replicated state directories.

## Boundaries and validation

No bulk movie ingestion, account rotation, Cloudflare cache proxy, or deployment
is configured. Cloudflare integration requires an eligible delivery product and
an origin approved for app streaming. Validate range/seek support, link expiry,
IP binding, traffic accounting and simultaneous playback before connecting apps.

Tests use local mocked responses and temporary files: they verify protocol and
failure handling without uploading files or accessing your account. Passing them
does not establish live provider compatibility. Stored responses and hashes must
never be committed. The account credential supplied in chat is not copied into
these sources or configured into the environment.
