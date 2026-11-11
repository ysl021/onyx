import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";
import { player } from "./player";

export type LyricLine = { timeMs: number; text: string };
export type Lyrics = { synced: boolean; lines: LyricLine[] };

export const lyrics = writable<Lyrics | null>(null);
export const lyricsLoading = writable(false);

let lastKey = "";

export async function loadLyrics(force = false) {
  const track = get(player);
  if (!track.title) {
    lyrics.set(null);
    return;
  }
  const key = `${track.artist}|${track.title}|${track.album}|${track.durationMs}`;
  if (!force && key === lastKey) return;
  lastKey = key;
  lyricsLoading.set(true);
  try {
    lyrics.set(
      await invoke<Lyrics | null>("lyrics_fetch", {
        artist: track.artist,
        title: track.title,
        album: track.album,
        durationMs: track.durationMs,
      }),
    );
  } catch {
    lyrics.set(null);
  } finally {
    lyricsLoading.set(false);
  }
}
