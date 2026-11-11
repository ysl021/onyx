import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";
import { currentPath, currentTrack, refreshPlayer } from "./player";
import { settings } from "./settings";

export type Video = {
  id: string;
  title: string;
  uploader: string;
  durationMs: number;
  thumbnail: string;
};

export type DownloadSection = {
  startMs: number;
  endMs: number;
};

export type DownloadJob = {
  id: string;
  videoId: string;
  title: string;
  artist: string;
  status: "queued" | "downloading" | "converting" | "done" | "error" | "cancelled";
  progress: number;
  message: string;
  outputs: string[];
  createdAt: number;
};

export const videos = writable<Video[]>([]);
export const searching = writable(false);
export const playingId = writable<string | null>(null);
export const downloads = writable<DownloadJob[]>([]);

export async function search(query: string, musicOnly = true) {
  const clean = query.trim();
  if (!clean) return;
  searching.set(true);
  try {
    videos.set(
      await invoke<Video[]>("youtube_search", {
        query: clean,
        musicOnly,
      }),
    );
  } finally {
    searching.set(false);
  }
}

export async function loadPlaylist(url: string) {
  const clean = url.trim();
  if (!clean) return;
  searching.set(true);
  try {
    videos.set(await invoke<Video[]>("youtube_playlist", { url: clean }));
  } finally {
    searching.set(false);
  }
}

export async function playVideo(video: Video) {
  playingId.set(video.id);
  try {
    const id = `yt:${video.id}`;
    currentPath.set(id);
    currentTrack.set({
      id,
      path: id,
      title: video.title,
      artist: video.uploader,
      album: "",
      durationMs: video.durationMs,
      hasCover: false,
      artwork: video.thumbnail || null,
    });
    const volume = get(settings).volume;
    await invoke("player_volume", { volume }).catch(() => {});
    await invoke("youtube_play", {
      id: video.id,
      title: video.title,
      artist: video.uploader,
      durationMs: video.durationMs,
    });
    await refreshPlayer();
  } finally {
    playingId.set(null);
  }
}

export async function loadDownloads() {
  try {
    downloads.set(await invoke<DownloadJob[]>("youtube_downloads"));
  } catch {
    // The backend is not ready yet.
  }
}

export async function addDownload(
  video: Video,
  sections: DownloadSection[],
) {
  downloads.set(
    await invoke<DownloadJob[]>("youtube_download_add", {
      videoId: video.id,
      title: video.title,
      artist: video.uploader,
      sections,
    }),
  );
}

export async function cancelDownload(id: string) {
  downloads.set(await invoke<DownloadJob[]>("youtube_download_cancel", { id }));
}

export async function removeDownload(id: string) {
  downloads.set(await invoke<DownloadJob[]>("youtube_download_remove", { id }));
}

export async function clearDownloads() {
  downloads.set(await invoke<DownloadJob[]>("youtube_download_clear"));
}
