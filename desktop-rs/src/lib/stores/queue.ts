import { writable } from "svelte/store";
import type { Track } from "./library";

export const queue = writable<Track[]>([]);
export const queueIndex = writable(0);
export const repeatOne = writable(false);
export const shuffle = writable(false);

export function setQueue(tracks: Track[], index: number) {
  queue.set(tracks);
  queueIndex.set(index);
}

export function toggleRepeat() {
  repeatOne.update((value) => !value);
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

// The helpers below avoid importing `get` into every caller.
let shuffleNow = false;
let repeatNow = false;
shuffle.subscribe((value) => (shuffleNow = value));
repeatOne.subscribe((value) => (repeatNow = value));

function shuffleValue() {
  return shuffleNow;
}

export function repeatValue() {
  return repeatNow;
}
