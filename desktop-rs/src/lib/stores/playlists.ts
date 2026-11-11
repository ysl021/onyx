import { invoke } from "@tauri-apps/api/core";
import { writable } from "svelte/store";

export type StreamTrack = {
  id: string;
  videoId: string;
  title: string;
  artist: string;
  durationMs: number;
  thumbnail?: string | null;
};

export type Playlist = {
  id: string;
  name: string;
  description: string;
  pinned: boolean;
  trackIds: string[];
  streams: StreamTrack[];
  createdAt: number;
};

export type PlaylistTrackInput = {
  id: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  thumbnail?: string | null;
};

export const playlists = writable<Playlist[]>([]);

export async function loadPlaylists() {
  try {
    playlists.set(await invoke<Playlist[]>("playlists_load"));
  } catch {
    // The backend is not ready yet.
  }
}

export async function createPlaylist(name: string) {
  try {
    playlists.set(await invoke<Playlist[]>("playlist_create", { name }));
  } catch {
    // Keep the current list.
  }
}

export async function renamePlaylist(id: string, name: string) {
  try {
    playlists.set(await invoke<Playlist[]>("playlist_rename", { id, name }));
  } catch {}
}

export async function updatePlaylist(
  id: string,
  name: string,
  description: string,
  pinned: boolean,
) {
  try {
    playlists.set(
      await invoke<Playlist[]>("playlist_update", {
        id,
        name,
        description,
        pinned,
      }),
    );
  } catch {}
}

export async function deletePlaylist(id: string) {
  try {
    playlists.set(await invoke<Playlist[]>("playlist_delete", { id }));
  } catch {}
}

export async function addTracksToPlaylist(id: string, trackIds: string[]) {
  try {
    playlists.set(
      await invoke<Playlist[]>("playlist_add_tracks", { id, trackIds }),
    );
  } catch {}
}

export async function addTrackToPlaylist(id: string, track: PlaylistTrackInput) {
  try {
    playlists.set(await invoke<Playlist[]>("playlist_add_track", { id, track }));
  } catch {}
}

export async function removeTrackFromPlaylist(id: string, trackId: string) {
  try {
    playlists.set(
      await invoke<Playlist[]>("playlist_remove_track", { id, trackId }),
    );
  } catch {}
}
