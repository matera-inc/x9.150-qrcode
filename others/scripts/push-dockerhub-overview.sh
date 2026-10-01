#!/usr/bin/env bash
#
# Push DOCKERHUB.md to the Docker Hub repository Overview.
#
#   ./others/scripts/push-dockerhub-overview.sh            # push
#   ./others/scripts/push-dockerhub-overview.sh --dry-run  # show what would change
#
# The Overview drifted for weeks once — it advertised Bitcoin, Ethereum, Polygon, Base, XRP and
# Arc, and "any ISO 4217 code", while the service refused every one of them BY NAME at creation.
# A public page promising what the service declines is worse than no page. Hence a script.
#
# Credentials come from what `docker login` already stored; nothing is prompted and nothing is
# echoed. If you use a credential helper rather than a plain config entry, export
# DOCKERHUB_USER and DOCKERHUB_TOKEN instead.
set -euo pipefail

REPO="${DOCKERHUB_REPO:-materainc/x9-qrcode}"
SOURCE="${1:-DOCKERHUB.md}"
[ "${1:-}" = "--dry-run" ] && SOURCE="DOCKERHUB.md"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '   \033[32mok\033[0m    %s\n' "$1"; }
die()  { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

[ -f "$SOURCE" ] || die "$SOURCE not found — run this from the repository root."

DRY_RUN=""
for arg in "$@"; do [ "$arg" = "--dry-run" ] && DRY_RUN="yes"; done

python3 - "$REPO" "$SOURCE" "${DRY_RUN:-}" <<'PY'
import base64, json, os, sys, urllib.error, urllib.request

repo, source, dry_run = sys.argv[1], sys.argv[2], sys.argv[3]

# Everything after the leading HTML comment — the comment is a note to maintainers, not content.
text = open(source).read()
if text.lstrip().startswith("<!--"):
    text = text.split("-->", 1)[1].lstrip("\n")

def credentials():
    user, token = os.environ.get("DOCKERHUB_USER"), os.environ.get("DOCKERHUB_TOKEN")
    if user and token:
        return user, token
    config = os.path.expanduser("~/.docker/config.json")
    if not os.path.exists(config):
        sys.exit("no ~/.docker/config.json and no DOCKERHUB_USER/DOCKERHUB_TOKEN")
    auths = json.load(open(config)).get("auths", {})
    entry = auths.get("https://index.docker.io/v1/") or auths.get("index.docker.io") or {}
    if "auth" not in entry:
        sys.exit("no stored Docker Hub credential — run `docker login`, or export "
                 "DOCKERHUB_USER and DOCKERHUB_TOKEN")
    return base64.b64decode(entry["auth"]).decode().split(":", 1)

def post(url, payload, headers=None):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **(headers or {})}, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())

user, secret = credentials()
print(f"   repo  {repo}")
print(f"   as    {user}")

try:
    jwt = post("https://hub.docker.com/v2/users/login/",
               {"username": user, "password": secret})["token"]
except urllib.error.HTTPError as e:
    sys.exit(f"Docker Hub refused the login ({e.code}). The stored credential may be a registry "
             f"token without Hub API scope — export DOCKERHUB_USER and DOCKERHUB_TOKEN with a "
             f"Personal Access Token that has 'Public Repo Read/Write'.")

headers = {"Authorization": f"JWT {jwt}"}

with urllib.request.urlopen(
        urllib.request.Request(f"https://hub.docker.com/v2/repositories/{repo}/",
                               headers=headers)) as response:
    live = json.loads(response.read()).get("full_description") or ""

if live.strip() == text.strip():
    print("   already identical — nothing to push")
    sys.exit(0)

print(f"   live  {len(live)} chars")
print(f"   new   {len(text)} chars")

if dry_run:
    import difflib
    diff = list(difflib.unified_diff(live.splitlines(), text.splitlines(),
                                     "docker hub", source, lineterm="", n=1))
    print("\n".join(diff[:60]) or "   (no textual difference)")
    print("\n   DRY RUN — nothing was pushed")
    sys.exit(0)

request = urllib.request.Request(
    f"https://hub.docker.com/v2/repositories/{repo}/",
    data=json.dumps({"full_description": text}).encode(),
    headers={"Content-Type": "application/json", **headers}, method="PATCH")
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        json.loads(response.read())
except urllib.error.HTTPError as e:
    detail = e.read().decode(errors="replace")[:200]
    if e.code == 403:
        sys.exit(
            "Docker Hub refused the write: 403 insufficient scope.\n"
            "\n"
            "  A registry Personal Access Token (dckr_...) can push IMAGES but cannot edit\n"
            "  repository metadata. The Overview needs a token with Hub API write scope, or a\n"
            "  JWT from an account password login.\n"
            "\n"
            "  Either export one:\n"
            "      DOCKERHUB_USER=<user> DOCKERHUB_TOKEN=<token> "
            "./others/scripts/push-dockerhub-overview.sh\n"
            "\n"
            "  or paste DOCKERHUB.md (everything under the HTML comment) into the Overview at\n"
            "      https://hub.docker.com/repository/docker/" + repo + "/general\n"
            "\n"
            "  NOTHING was written — the live page is unchanged.")
    sys.exit(f"Docker Hub refused the write ({e.code}): {detail}")

# Read it back rather than trusting the response: the point of this script is that the page
# says what we think it says.
with urllib.request.urlopen(
        urllib.request.Request(f"https://hub.docker.com/v2/repositories/{repo}/",
                               headers=headers)) as response:
    confirmed = json.loads(response.read()).get("full_description") or ""

if confirmed.strip() != text.strip():
    sys.exit("PATCH returned success but the page does not match what was sent.")

print(f"   pushed and confirmed from the registry — {len(confirmed)} chars")
PY
