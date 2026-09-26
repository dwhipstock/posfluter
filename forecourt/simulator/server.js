// Forecourt simulator: plays the FDC plus the pumps for Pronghorn Fuel & Market demos.
// Node >= 22, built-ins only. See README.md.
import { createSimulator } from './lib/api.js';

const port = Number(process.env.FDC_PORT ?? 8086);
const host = process.env.FDC_HOST ?? '0.0.0.0';
const pumps = Number(process.env.FDC_PUMPS ?? 8);
const speed = Number(process.env.FDC_SPEED ?? 1);

if (!Number.isInteger(pumps) || pumps < 1 || pumps > 64) {
  console.error('FDC_PUMPS must be an integer 1..64');
  process.exit(1);
}

const sim = createSimulator({ pumps, speed });
const bound = await sim.listen(port, host);
console.log(`forecourt simulator: ${pumps} pumps, speed ${sim.model.speed}x`);
console.log(`  panel   http://${host === '0.0.0.0' ? 'localhost' : host}:${bound}/`);
console.log(`  FDC API http://${host === '0.0.0.0' ? 'localhost' : host}:${bound}/fdc/v1/status`);

const shutdown = () => sim.close().then(() => process.exit(0));
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
