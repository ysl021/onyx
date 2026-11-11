import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { writable } from "svelte/store";

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

export async function refreshPlayer() {
  try {
    const state = await invoke<PlayerState>("player_state");
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
    await invoke("player_load", { path: selected });
    await refreshPlayer();
  }
}

export async function seekTo(seconds: number) {
  await invoke("player_seek", { seconds });
  await refreshPlayer();
}

/** Poll mpv four times a second; returns the cleanup function. */
export function startPolling() {
  const id = setInterval(refreshPlayer, 250);
  return () => clearInterval(id);
}
