#!/bin/bash
# Queue builds on a throwaway, agent-less TeamCity and print the queue order the plugin produced.
set -eu
TC=http://127.0.0.1:18111
TOKEN=$1
admin() { curl -sS --fail-with-body -u ":$TOKEN" -H 'Origin: http://127.0.0.1:18111' "$@"; }
as() { local u=$1; shift; curl -sS --fail-with-body -u "$u:secret-$u" -H 'Origin: http://127.0.0.1:18111' "$@"; }

project() {
  admin -X POST $TC/app/rest/projects -H 'Content-Type: application/xml' \
    -d "<newProjectDescription name='$1' id='$1'><parentProject locator='id:_Root'/></newProjectDescription>" >/dev/null
}
config() {  # project, config id
  admin -X POST $TC/app/rest/projects/id:$1/buildTypes -H 'Content-Type: application/xml' \
    -d "<newBuildTypeDescription name='$2' id='$2'/>" >/dev/null
}
user() {
  admin -X POST $TC/app/rest/users -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"secret-$1\"}" >/dev/null
  admin -X PUT $TC/app/rest/users/username:$1/roles/PROJECT_DEVELOPER/g >/dev/null
}
queue_as() {  # user, config id
  as $1 -X POST $TC/app/rest/buildQueue -H 'Content-Type: application/json' -d "{\"buildType\":{\"id\":\"$2\"}}" >/dev/null
  sleep 1
}
show_queue() {
  admin -H 'Accept: application/json' "$TC/app/rest/buildQueue?fields=build(buildTypeId)" \
    | python3 -c 'import json,sys; print(" ".join(b["buildTypeId"] for b in json.load(sys.stdin).get("build",[])))'
}
clear_queue() { admin -X DELETE "$TC/app/rest/buildQueue" >/dev/null; }

set +e; project Heavy; project Light; project Shared
for c in H1 H2 H3; do config Heavy $c; done
config Light L1
for c in S1 S2 S3 S4; do config Shared $c; done
user alice; user bob; set -e

echo "== projects: queue H1 H2 H3 L1 (expect H1 L1 H2 H3)"
for c in H1 H2 H3 L1; do queue_as alice $c; done
sleep 2; show_queue; clear_queue

echo "== users in one project: alice S1 S2 S3, bob S4 (expect S1 S4 S2 S3)"
for c in S1 S2 S3; do queue_as alice $c; done; queue_as bob S4
sleep 2; show_queue; clear_queue
