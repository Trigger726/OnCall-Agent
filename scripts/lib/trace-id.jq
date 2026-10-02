# Tempo search may omit leading zeroes. Normalize hex text, never numeric values
# (128-bit IDs exceed JSON/IEEE-754 integer precision). Reject malformed/all-zero IDs.
def canonical_trace_id:
  if type == "string" and test("^[0-9a-fA-F]{1,32}$") and test("[1-9a-fA-F]") then
    ascii_downcase as $id | ("00000000000000000000000000000000" + $id)[-32:]
  else error("Invalid non-zero hexadecimal trace ID") end;
