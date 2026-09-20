// Trigger a browser download from an in-memory blob. Works on mobile Safari/
// Chrome (an <a download> click), which is where owners actually pull reports.
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Revoke on the next tick so the download has grabbed the URL first.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
