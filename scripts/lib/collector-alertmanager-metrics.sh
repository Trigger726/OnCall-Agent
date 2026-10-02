#!/usr/bin/env bash
# Default v0.34.1 metrics have integration only. This isolated config contains
# exactly one webhook receiver; do not invent a receiver label or treat missing
# series as zero. Attempts alone do not prove successful business acceptance.
alertmanager_webhook_metric() {
  case "$1" in
    alertmanager_notifications_total|alertmanager_notifications_failed_total|alertmanager_notification_requests_failed_total) ;;
    *) return 1 ;;
  esac
  awk -v metric="$1" '$1 ~ ("^" metric "\\{") && /integration="webhook"/ { sum += $2; found=1 }
    END { if (!found) exit 1; printf "%.0f\n", sum }' "$2"
}

alertmanager_webhook_attempts_without_failures() {
  # Explicit returns remain fail-closed even inside command substitutions or
  # conditionals, where Bash does not reliably propagate errexit.
  [[ "$(alertmanager_webhook_metric alertmanager_notifications_failed_total "$1")" == 0 ]] || return 1
  [[ "$(alertmanager_webhook_metric alertmanager_notification_requests_failed_total "$1")" == 0 ]] || return 1
  alertmanager_webhook_metric alertmanager_notifications_total "$1"
}
