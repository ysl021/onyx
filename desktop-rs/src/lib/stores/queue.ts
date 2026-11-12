import { writable } from "svelte/store";
import type { Track } from "./library";

export type RepeatMode = "off" | "one" | "all";

export const queue = writable<Track[]>([]);
export const queueIndex = writable(0);
export const repeatMode = writable<RepeatMode>("off");
export const shuffle = writable(false);

export function setQueue(tracks: Track[], index: number) {
  queue.set(tracks);
  queueIndex.set(index);
}

export function cycleRepeat() {
  repeatMode.update((mode) =>
    mode === "off" ? "one" : mode === "one" ? "all" : "off",
  );
}

export function setRepeatMode(mode: RepeatMode) {
  repeatMode.set(mode);
}

export function toggleShuffle() {
  shuffle.update((value) => !value);
}

export function nextQueueIndex(current: number, length: number): number {
  if (length <= 1) return 0;
  if (shuffleValue()) {
    let next = current;
    while (next === current) next = Math.floor(Math.random() * length);
    return next;
  }
  return (current + 1) % length;
}

export function previousQueueIndex(current: number, length: number): number {
  if (length <= 1) return 0;
  if (shuffleValue()) {
    let next = current;
    while (next === current) next = Math.floor(Math.random() * length);
    return next;
  }
  return (current - 1 + length) % length;
}

// The helper below avoids importing `get` into every caller.
let shuffleNow = false;
shuffle.subscribe((value) => (shuffleNow = value));

export function shuffleValue() {
  return shuffleNow;
}
