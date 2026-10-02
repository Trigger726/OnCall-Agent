#!/usr/bin/env bash
set -euo pipefail
source scripts/lib/verify-collector-saturation.sh
evidence_dir=target/tracing-it/saturation-assertions
mkdir -p "$evidence_dir"
collector_saturation_recovery_proven true true 0 3 true
collector_saturation_recovery_proven true true 4 3 true
reject_count=0
for entry in 'true true 0 9 false' 'true true 4 9 false' 'false true 4 9 true' 'true false 4 9 true' 'true true 12 9 true' 'true true 4 2 true' 'true true -1 9 true' 'true true wrong 9 true'; do
  read -r fired drained count stable control <<< "$entry"
  if collector_saturation_recovery_proven "$fired" "$drained" "$count" "$stable" "$control"; then
    echo "Unproven saturation recovery accepted: $entry" >&2; exit 1
  fi
  reject_count=$(( reject_count + 1 ))
done
jq -n --argjson rejected "$reject_count" '{status:"PASS",provenRecoveriesAccepted:2,unprovenRecoveriesRejected:$rejected,zeroBurstRecoveryRequiresIndependentControl:true}' > "$evidence_dir/result.json"
cat "$evidence_dir/result.json"
