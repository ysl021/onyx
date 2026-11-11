import { invoke } from "@tauri-apps/api/core";
import { get, writable } from "svelte/store";

export type PanelPosition = "bottom" | "top" | "left" | "right";

export type Settings = {
  volume: number;
  discordEnabled: boolean;
  discordClientId: string;
  hubPort: number;
  hubName: string;
  theme: string;
  language: string;
  panelPosition: PanelPosition;
  audioDevice: string;
  coverBackdrop: boolean;
  youtubeProxy: string;
  volumeStep: number;
  eqEnabled: boolean;
  eqBands: number[];
  musicFolders: string[];
};

const defaults: Settings = {
  volume: 80,
  discordEnabled: false,
  discordClientId: "1568847197397717073",
  hubPort: 8787,
  hubName: "",
  theme: "dark",
  language: "ru",
  panelPosition: "bottom",
  audioDevice: "auto",
  coverBackdrop: false,
  youtubeProxy: "",
  volumeStep: 5,
  eqEnabled: false,
  eqBands: [0, 0, 0, 0, 0],
  musicFolders: [],
};

export const settings = writable<Settings>(defaults);

export async function loadSettings() {
  try {
    const loaded = await invoke<Partial<Settings>>("load_settings");
    // The Kotlin build stores enum names like "BOTTOM" / "DARK"; normalize them.
    const rawPanel = String(loaded.panelPosition ?? "bottom").toLowerCase();
    const panelPosition = (["bottom", "top", "left", "right"].includes(rawPanel)
      ? rawPanel
      : "bottom") as PanelPosition;
    const theme = String(loaded.theme ?? "dark").toLowerCase() === "light"
      ? "light"
      : "dark";
    const rawBands = Array.isArray(loaded.eqBands) ? loaded.eqBands : [];
    const eqBands = [0, 1, 2, 3, 4].map((index) =>
      Number(rawBands[index] ?? 0),
    );
    settings.set({ ...defaults, ...loaded, panelPosition, theme, eqBands });
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

export async function setTheme(theme: "dark" | "light") {
  await saveSettings({ ...get(settings), theme });
}

export async function setVolume(volume: number) {
  await saveSettings({ ...get(settings), volume });
  try {
    await invoke("player_volume", { volume });
  } catch {
    // mpv may not be running yet.
  }
}

export async function setAudioDevice(audioDevice: string) {
  await saveSettings({ ...get(settings), audioDevice });
  try {
    await invoke("player_set_audio_device", { device: audioDevice });
  } catch {
    // mpv starts with the next track.
  }
}

export async function setEq(enabled: boolean, bands: number[]) {
  await saveSettings({ ...get(settings), eqEnabled: enabled, eqBands: bands });
  try {
    await invoke("player_set_eq", { enabled, bands });
  } catch {
    // mpv starts with the next track.
  }
}
