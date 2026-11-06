export function routineDayIsCurrent(snapshot: { day_started_at: string; next_day_at: string }, now = Date.now()) {
  return now >= new Date(snapshot.day_started_at).getTime() && now < new Date(snapshot.next_day_at).getTime()
}
