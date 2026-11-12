import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { get, writable } from "svelte/store";
import { tracks, type Track } from "./library";
import {
  nextQueueIndex,
  previousQueueIndex,
  queue,
  queueIndex,
  repeatOne,
} from "./queue";
import { settings } from "./settings";

export type PlayerState = {
  title: string;
  artist: string;
  album: string;
  playing: boolean;
  positionMs: number;
  durationMs: number;
};

export const player = writable<PlayerState>({
  title: "",
  artist: "",
  album: "",
  playing: false,
  positionMs: 0,
  durationMs: 0,
});

export const cover = writable<string | null>(null);
export const currentPath = writable<string | null>(null);
export const currentTrack = writable<Track | null>(null);

export async function setCoverFor(path: string) {
  try {
    cover.set(await invoke<string | null>("track_cover", { path }));
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
  const list = get(queue);
  if (list.length === 0) return;
  const index = nextQueueIndex(get(queueIndex), list.length);
  queueIndex.set(index);
  await playTrack(list[index].path, list[index]);
}

export async function playPrevious() {
  const list = get(queue);
  if (list.length === 0) return;
  const index = previousQueueIndex(get(queueIndex), list.length);
  queueIndex.set(index);
  await playTrack(list[index].path, list[index]);
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
  let wasPlaying = false;
  let busy = false;
  const id = setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      await refreshPlayer();
      const state = get(player);
      const finished =
        wasPlaying &&
        !state.playing &&
        state.durationMs > 0 &&
        (state.positionMs >= state.durationMs - 1500 || state.positionMs === 0);
      wasPlaying = state.playing;
      if (!finished) return;
      if (get(repeatOne)) {
        const path = get(currentPath);
        if (path) await playTrack(path, get(currentTrack) ?? undefined);
      } else {
        await playNext();
      }
    } finally {
      busy = false;
    }
  }, 500);
  return () => clearInterval(id);
}
