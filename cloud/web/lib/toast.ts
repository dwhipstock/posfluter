import { ApiError } from "./api";

export type ToastKind = "success" | "error" | "info";
export type ToastMsg = { id: number; kind: ToastKind; message: string };

type Listener = (t: ToastMsg) => void;
let listeners: Listener[] = [];
let nextId = 1;

export function toast(kind: ToastKind, message: string) {
  const t = { id: nextId++, kind, message };
  listeners.forEach((l) => l(t));
}

export function onToast(l: Listener): () => void {
  listeners.push(l);
  return () => {
    listeners = listeners.filter((x) => x !== l);
  };
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  return "Something went wrong";
}

export function toastError(e: unknown) {
  toast("error", errorMessage(e));
}
