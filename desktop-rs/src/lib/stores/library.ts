import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";
import { saveSettings, settings } from "./settings";

export type Track = {
  id: string;
  path: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  hasCover: boolean;
};

export const tracks = writable<Track[]>([]);
export const scanning = writable(false);

export async function loadTracks() {
  try {
    tracks.set(await invoke<Track[]>("library_tracks"));
  } catch {
    // The backend is not ready yet.
  }
}

export async function scanFolder(folder: string) {
  scanning.set(true);
  try {
    const found = await invoke<Track[]>("library_scan", { folders: [folder] });
    tracks.set(found);
    const current = get(settings);
    const folders = current.musicFolders.includes(folder)
      ? current.musicFolders
      : [...current.musicFolders, folder];
    await saveSettings({ ...current, musicFolders: folders });
    return found;
  } finally {
    scanning.set(false);
  }
}

export async function scanSavedFolders() {
  const folders = get(settings).musicFolders;
  if (folders.length === 0) return;
  scanning.set(true);
  try {
    tracks.set(await invoke<Track[]>("library_scan", { folders }));
  } catch {
    // Ignore unreadable folders.
  } finally {
    scanning.set(false);
  }
}

export async function trackCover(path: string): Promise<string | null> {
  try {
    return await invoke<string | null>("track_cover", { path });
  } catch {
    return null;
  }
}
