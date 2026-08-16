/**
 * Formatting helpers.
 *
 * The backend speaks UTC ISO-8601 everywhere (`Instant`). The browser converts
 * to the viewer's local zone for display. Storing UTC and formatting at the
 * edge is what keeps "10:00" meaning the same moment for a server in Frankfurt
 * and a patient in Jabalpur.
 */

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString([], {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function todayIso(): string {
  const now = new Date();
  const offsetMinutes = now.getTimezoneOffset();
  return new Date(now.getTime() - offsetMinutes * 60_000).toISOString().slice(0, 10);
}

export function countdown(seconds: number): string {
  if (seconds <= 0) {
    return 'expired';
  }
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${minutes}:${String(remainder).padStart(2, '0')}`;
}
