import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";

export const sleepRemainingMs = writable(0);

let timer: ReturnType<typeof setInterval> | null = null;

export function cancelSleepTimer() {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
  sleepRemainingMs.set(0);
}

export function startSleepTimer(minutes: number) {
  cancelSleepTimer();
  if (minutes <= 0) return;
  sleepRemainingMs.set(minutes * 60_000);
  timer = setInterval(async () => {
    const next = get(sleepRemainingMs) - 1000;
    if (next <= 0) {
      sleepRemainingMs.set(0);
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
      await invoke("player_pause").catch(() => {});
    } else {
      sleepRemainingMs.set(next);
    }
  }, 1000);
}
