import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";

export type PanelPosition = "bottom" | "top" | "left" | "right";

export type Settings = {
  volume: number;
  discordEnabled: boolean;
  hubPort: number;
  theme: string;
  language: string;
  panelPosition: PanelPosition;
  musicFolders: string[];
};

const defaults: Settings = {
  volume: 80,
  discordEnabled: false,
  hubPort: 8787,
  theme: "dark",
  language: "ru",
  panelPosition: "bottom",
  musicFolders: [],
};

export const settings = writable<Settings>(defaults);

export async function loadSettings() {
  try {
    const loaded = await invoke<Partial<Settings>>("load_settings");
    // The Kotlin build stores enum names like "BOTTOM"; normalize them.
    const rawPanel = String(loaded.panelPosition ?? "bottom").toLowerCase();
    const panelPosition = (["bottom", "top", "left", "right"].includes(rawPanel)
      ? rawPanel
      : "bottom") as PanelPosition;
    settings.set({ ...defaults, ...loaded, panelPosition });
  } catch {
    // Keep defaults until the Rust backend is available.
  }
}

export async function saveSettings(next: Settings) {
  settings.set(next);
  try {
    await invoke("save_settings", { settings: next });
  } catch {
    // The UI still works if persistence fails.
  }
}

export async function setPanelPosition(panelPosition: PanelPosition) {
  await saveSettings({ ...get(settings), panelPosition });
}

export async function setVolume(volume: number) {
  await saveSettings({ ...get(settings), volume });
  try {
    await invoke("player_volume", { volume });
  } catch {
    // mpv may not be running yet.
  }
}
