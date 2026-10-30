// Servidor local para renderizar el auto en 360°: sirve la página, three.js y el modelo,
// y guarda cada cuadro que la página le envía en ../frames/frame_XX.png
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const MODELS = path.join(ROOT, '..', 'models');
const FRAMES = path.join(ROOT, '..', 'frames');
fs.mkdirSync(FRAMES, { recursive: true });

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.glb': 'model/gltf-binary' };

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'POST' && url.pathname === '/frame') {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      const data = Buffer.concat(chunks).toString().replace(/^data:image\/png;base64,/, '');
      const name = 'frame_' + String(url.searchParams.get('i')).padStart(2, '0') + '.png';
      fs.writeFileSync(path.join(FRAMES, name), Buffer.from(data, 'base64'));
      console.log('guardado', name);
      res.end('ok');
    });
    return;
  }
  if (req.method === 'POST' && url.pathname === '/clear') {
    for (const f of fs.readdirSync(FRAMES)) fs.unlinkSync(path.join(FRAMES, f));
    res.end('ok');
    return;
  }
  const base = url.pathname.startsWith('/models/') ? MODELS : ROOT;
  const rel = url.pathname.startsWith('/models/') ? url.pathname.slice('/models/'.length) : url.pathname.slice(1) || 'render.html';
  const file = path.join(base, path.normalize(rel));
  if (!file.startsWith(base) || !fs.existsSync(file)) { res.statusCode = 404; res.end('no'); return; }
  res.setHeader('Content-Type', TYPES[path.extname(file)] || 'application/octet-stream');
  fs.createReadStream(file).pipe(res);
}).listen(8765, '127.0.0.1', () => console.log('listo en http://localhost:8765'));
