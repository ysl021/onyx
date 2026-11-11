import { invoke } from "@tauri-apps/api/core";
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
    // The Rust backend is not wired to mpv yet; keep the empty card.
  }
}
