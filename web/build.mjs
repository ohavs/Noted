// Bundles the web app (src/main.js) and the TV screen (src/tv.js) into public/, Firebase included.
import { build } from 'esbuild';

const watch = process.argv.includes('--dev');
await build({
  entryPoints: { app: 'src/main.js', tv: 'src/tv.js' },
  outdir: 'public',
  bundle: true,
  format: 'esm',
  splitting: true,
  chunkNames: 'chunks/[name]-[hash]',
  minify: !watch,
  sourcemap: watch,
  target: ['es2022'],
  legalComments: 'none',
});
console.log('built public/app.js, public/tv.js');
