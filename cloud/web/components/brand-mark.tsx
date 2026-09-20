export function BarcodeMark({ compact = false }: { compact?: boolean }) {
  const bars = [1, 2, 1, 3, 1, 2, 3, 1, 2];

  return (
    <span className="inline-flex items-center gap-2" aria-label="Copper Lantern Manager">
      <span
        aria-hidden="true"
        className="flex h-6 items-stretch gap-px rounded-sm bg-white px-1.5 py-1 shadow-sm"
      >
        {bars.map((width, index) => (
          <span key={index} className="bg-accent" style={{ width }} />
        ))}
      </span>
      <span className="flex flex-col leading-none">
        <span className="text-sm font-bold uppercase tracking-[0.16em]">Copper Lantern</span>
        {!compact && (
          <span className="mt-1 text-[8px] font-semibold uppercase tracking-[0.32em] text-blue-200">
            Manager
          </span>
        )}
      </span>
    </span>
  );
}
