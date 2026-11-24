export const CONNECTION_ATTEMPT_TIMEOUT_MS = 15000

export function connectionAttemptTimedOut(elapsedMs, limitMs = CONNECTION_ATTEMPT_TIMEOUT_MS) {
  return elapsedMs >= limitMs
}
