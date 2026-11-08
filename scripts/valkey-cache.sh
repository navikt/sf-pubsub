#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s {count-entries|clear} <valkey-resource-name> [credential-secret-name]\n' "$0" >&2
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

[[ $# -ge 2 && $# -le 3 ]] || { usage; exit 1; }
operation=$1
name=$2
secret_name=${3:-}
case "$operation" in
    count-entries|clear) ;;
    *) usage; exit 1 ;;
esac
[[ "$name" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || fail "Invalid Valkey resource name."
[[ -z "$secret_name" || "$secret_name" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || fail "Invalid secret name."

for tool in kubectl jq redis-cli; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required command not found: $tool"
done

context=$(kubectl config current-context)
[[ -n "$context" ]] || fail "No current kubectl context."
namespace=$(kubectl --context "$context" config view --minify -o 'jsonpath={.contexts[0].context.namespace}')
namespace=${namespace:-default}
kube=(kubectl --context "$context" --namespace "$namespace")
"${kube[@]}" get valkey "$name" -o name >/dev/null

printf 'Context: %s\nNamespace: %s\nValkey: %s\n' "$context" "$namespace" "$name"

if [[ "$operation" == clear ]]; then
    confirmation="$context/$namespace/$name"
    printf 'This deletes ALL keys in ALL logical databases of this Valkey instance.\n' >&2
    printf 'Type %s to confirm: ' "$confirmation" >&2
    answer=
    if ! IFS= read -r answer </dev/tty; then
        fail "Interactive confirmation is required."
    fi
    [[ "$answer" == "$confirmation" ]] || fail "Confirmation did not match; nothing cleared."
fi

if [[ -z "$secret_name" ]]; then
    if ! applications=$("${kube[@]}" get aivenapplications.aiven.nais.io -o json); then
        fail "Cannot discover credential secret names. Supply an authorized credential secret name as the third argument."
    fi
    secret_name=$(jq -re --arg name "$name" --arg namespace "$namespace" '
        [
            .items[]
            | .spec.valkey[]?
            | select(("valkey-" + $namespace + "-" + .instance) == $name)
            | .secretName
            | select(type == "string" and length > 0)
        ]
        | unique
        | if length == 0 then
            error("No credential secret reference found; supply the secret name as the third argument")
          else
            .[0]
          end
    ' <<<"$applications")
    unset applications
fi

if ! secret_json=$("${kube[@]}" get secret "$secret_name" -o json); then
    fail "Cannot read credential secret '$secret_name'. Obtain authorized secret-read access from the namespace owners; listing Valkey resources does not grant credential access."
fi

credentials=$(jq -ce --arg name "$name" '
    . as $secret
    | [
        ($secret.metadata.annotations // {} | to_entries[])
        | select(
            (.key | endswith(".valkey.aiven.nais.io/serviceName"))
            and .value == $name
        )
        | (.key | split(".")[0] | gsub("[^a-z0-9]"; "_") | ascii_upcase) as $suffix
        | {
            secret: $secret.metadata.name,
            host: ($secret.data["VALKEY_HOST_" + $suffix] // "" | @base64d),
            port: ($secret.data["VALKEY_PORT_" + $suffix] // "" | @base64d),
            username: ($secret.data["VALKEY_USERNAME_" + $suffix] // "" | @base64d),
            password: ($secret.data["VALKEY_PASSWORD_" + $suffix] // "" | @base64d)
        }
    ]
    | if length == 0 then
        error("The selected secret does not reference this Aiven Valkey instance")
      else
        .[0]
      end
    | if ([.host, .port, .username, .password] | all(length > 0))
         and (.port | test("^[0-9]+$")) then
        .
      else
        error("Valkey credential secret has missing or invalid connection fields")
      end
' <<<"$secret_json")
unset secret_json

host=$(jq -r '.host' <<<"$credentials")
port=$(jq -r '.port' <<<"$credentials")
username=$(jq -r '.username' <<<"$credentials")
password=$(jq -r '.password' <<<"$credentials")
printf 'Credential secret: %s\n' "$(jq -r '.secret' <<<"$credentials")"
unset credentials

client=(redis-cli --tls --raw -e -h "$host" -p "$port" --user "$username")

count_entries() {
    local keyspace
    keyspace=$(REDISCLI_AUTH="$password" "${client[@]}" INFO keyspace)
    printf '%s\n' "$keyspace" | awk '
        /^# Keyspace\r?$/ { valid = 1 }
        /^db[0-9]+:/ {
            split($0, fields, ":")
            split(fields[2], values, ",")
            split(values[1], keys, "=")
            total += keys[2]
        }
        END {
            if (!valid) {
                print "Error: Unexpected INFO keyspace response." > "/dev/stderr"
                exit 1
            }
            printf "Entries across all logical databases: %.0f\n", total
        }
    '
}

count_entries
if [[ "$operation" == clear ]]; then
    REDISCLI_AUTH="$password" "${client[@]}" FLUSHALL SYNC
    count_entries
fi
