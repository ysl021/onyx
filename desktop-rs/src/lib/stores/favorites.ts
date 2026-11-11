import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";
import { tracks } from "./library";
import type { Track } from "./library";

export type FavoriteRecord = {
  id: string;
  videoId?: string | null;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  artwork?: string | null;
  at: number;
};

export const favorites = writable<string[]>([]);
export const favoriteRecords = writable<FavoriteRecord[]>([]);

export function favoriteIdOf(track: Track): string {
  if (track.path && (track.path.includes("\\") || track.path.includes("/"))) {
    return track.path;
  }
  if (track.id.startsWith("yt:") || track.path.startsWith("yt:")) {
    return track.id.startsWith("yt:") ? track.id : track.path;
  }
  return `yt:${track.id}`;
}

export async function loadFavorites() {
  try {
    const list = await invoke<FavoriteRecord[]>("favorites_load");
    favoriteRecords.set(list);
    favorites.set(list.map((item) => item.id));
  } catch {
    // The backend is not ready yet.
  }
}

export async function toggleFavorite(track: Track) {
  const id = favoriteIdOf(track);
  const record: FavoriteRecord = {
    id,
    videoId: id.startsWith("yt:") ? id.slice(3) : null,
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationMs: track.durationMs,
    artwork: track.artwork ?? null,
    at: Date.now(),
  };
  try {
    const list = await invoke<FavoriteRecord[]>("favorites_toggle", {
      track: record,
    });
    favoriteRecords.set(list);
    favorites.set(list.map((item) => item.id));
  } catch {
    // Keep the current list.
  }
}

/// The old Rust client stored only paths in localStorage. Move them into the
/// backend once so likes survive the switch to the synced favorites file.
export async function migrateLegacyFavorites() {
  const LEGACY_KEY = "onyx.favorites";
  try {
    const raw = localStorage.getItem(LEGACY_KEY);
    if (!raw) return;
    const paths = JSON.parse(raw) as string[];
    if (Array.isArray(paths) && paths.length > 0) {
      const library = get(tracks);
      for (const path of paths) {
        const track = library.find(
          (item) => item.path === path || item.id === path,
        );
        if (track) await toggleFavorite(track);
      }
    }
    localStorage.removeItem(LEGACY_KEY);
  } catch {
    // A broken legacy value must not block startup.
  }
}
