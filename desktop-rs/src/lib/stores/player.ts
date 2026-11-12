import { convertFileSrc, invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { get, writable } from "svelte/store";
import { tracks, type Track } from "./library";
import {
  previousQueueIndex,
  queue,
  queueIndex,
  repeatMode,
  shuffle,
} from "./queue";
import { settings } from "./settings";

export type PlayerState = {
  title: string;
  artist: string;
  album: string;
  playing: boolean;
  positionMs: number;
  durationMs: number;
  eof: boolean;
};

export const player = writable<PlayerState>({
  title: "",
  artist: "",
  album: "",
  playing: false,
  positionMs: 0,
  durationMs: 0,
  eof: false,
});

export const cover = writable<string | null>(null);
export const currentPath = writable<string | null>(null);
export const currentTrack = writable<Track | null>(null);

export async function setCoverFor(path: string) {
  try {
    const value = await invoke<string | null>("track_cover", { path });
    cover.set(value ? convertFileSrc(value) : null);
  } catch {
    cover.set(null);
  }
}

export async function playTrack(path: string, track?: Track) {
  const fallback =
    track ??
    get(tracks).find((item) => item.path === path || item.id === path) ??
    null;
  currentTrack.set(fallback);
  currentPath.set(path);
  const volume = get(settings).volume;
  await invoke("player_volume", { volume }).catch(() => {});
  await invoke("player_load", { path });
  await refreshPlayer();
  await setCoverFor(path);
}

export async function playNext() {
  await advance(true);
}

export async function playPrevious() {
  const list = get(queue);
  if (list.length === 0) return;
  if (get(player).positionMs > 4000) {
    await seekTo(0);
    return;
  }
  const index = previousQueueIndex(get(queueIndex), list.length);
  queueIndex.set(index);
  await playTrack(list[index].path, list[index]);
}

async function advance(manual: boolean) {
  const list = get(queue);
  if (list.length === 0) return;
  const current = get(queueIndex);
  const mode = get(repeatMode);
  if (!manual && mode === "one") {
    const track = list[current];
    if (track) await playTrack(track.path, track);
    return;
  }
  let next = current + 1;
  if (get(shuffle) && list.length > 1) {
    next = current;
    while (next === current) next = Math.floor(Math.random() * list.length);
  } else if (next >= list.length) {
    if (mode === "all" || manual) {
      next = 0;
    } else {
      await invoke("player_pause").catch(() => {});
      player.update((state) => ({ ...state, playing: false, eof: true }));
      return;
    }
  }
  queueIndex.set(next);
  await playTrack(list[next].path, list[next]);
}

export async function playQueueAt(index: number) {
  const list = get(queue);
  if (index < 0 || index >= list.length) return;
  queueIndex.set(index);
  await playTrack(list[index].path, list[index]);
}

export async function refreshPlayer() {
  try {
    const state = await invoke<PlayerState>("player_state");
    const fallback = get(currentTrack);
    if (fallback) {
      state.title = state.title || fallback.title;
      state.artist = state.artist || fallback.artist;
      state.album = state.album || fallback.album;
      state.durationMs = state.durationMs || fallback.durationMs;
    }
    player.set(state);
  } catch {
    // mpv is not running yet; keep the empty card.
  }
}

export async function togglePlayer() {
  const current = get(player);
  if (current.eof) {
    const path = get(currentPath);
    if (path) {
      await playTrack(path, get(currentTrack) ?? undefined);
      return;
    }
  }
  try {
    const state = await invoke<PlayerState>("player_toggle");
    player.set(state);
  } catch {
    await refreshPlayer();
  }
}

export async function openAudioFile() {
  const selected = await open({
    multiple: false,
    filters: [
      {
        name: "Audio",
        extensions: ["mp3", "flac", "m4a", "aac", "ogg", "opus", "wav"],
      },
    ],
  });
  if (typeof selected === "string") {
    currentTrack.set(null);
    currentPath.set(selected);
    const volume = get(settings).volume;
    await invoke("player_volume", { volume }).catch(() => {});
    await invoke("player_load", { path: selected });
    await refreshPlayer();
    await setCoverFor(selected);
  }
}

export async function seekTo(seconds: number) {
  await invoke("player_seek", { seconds });
  await refreshPlayer();
}

/** Poll mpv twice a second and advance the queue when a track ends. */
export function startPolling() {
  let busy = false;
  let handledEof = false;
  const id = setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      await refreshPlayer();
      const state = get(player);
      if (state.eof && !handledEof) {
        handledEof = true;
        await advance(false);
      } else if (!state.eof) {
        handledEof = false;
      }
    } finally {
      busy = false;
    }
  }, 500);
  return () => clearInterval(id);
}
