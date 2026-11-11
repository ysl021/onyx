import { writable } from "svelte/store";
import type { Track } from "./library";

export const queue = writable<Track[]>([]);
export const queueIndex = writable(0);

export function setQueue(tracks: Track[], index: number) {
  queue.set(tracks);
  queueIndex.set(index);
}
