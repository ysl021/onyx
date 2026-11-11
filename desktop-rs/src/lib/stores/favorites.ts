import { writable } from "svelte/store";

const KEY = "onyx.favorites";

function load(): string[] {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}

export const favorites = writable<string[]>(load());

favorites.subscribe((value) => {
  try {
    localStorage.setItem(KEY, JSON.stringify(value));
  } catch {
    // Storage can be unavailable; the UI still works for this session.
  }
});

export function toggleFavorite(path: string) {
  favorites.update((list) =>
    list.includes(path) ? list.filter((item) => item !== path) : [path, ...list],
  );
}
