import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";
import { currentTrack, refreshPlayer } from "./player";
import { settings } from "./settings";

export type Video = {
  id: string;
  title: string;
  uploader: string;
  durationMs: number;
  thumbnail: string;
};

export const videos = writable<Video[]>([]);
export const searching = writable(false);
export const playingId = writable<string | null>(null);

export async function search(query: string) {
  const clean = query.trim();
  if (!clean) return;
  searching.set(true);
  try {
    videos.set(await invoke<Video[]>("youtube_search", { query: clean }));
  } finally {
    searching.set(false);
  }
}

export async function playVideo(video: Video) {
  playingId.set(video.id);
  try {
    currentTrack.set({
      id: video.id,
      path: video.id,
      title: video.title,
      artist: video.uploader,
      album: "",
      durationMs: video.durationMs,
      hasCover: false,
    });
    const volume = get(settings).volume;
    await invoke("player_volume", { volume }).catch(() => {});
    await invoke("youtube_play", { id: video.id });
    await refreshPlayer();
  } finally {
    playingId.set(null);
  }
}
