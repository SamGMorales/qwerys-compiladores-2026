/**
 * Servidor local para probar la PWA con build de producción.
 * - SPA: rutas (/auth, /analyzer, …) → index.html (recarga sin 404)
 * - Proxy: /api/** → backend Spring (por defecto http://localhost:8080)
 */
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '../dist/qwerys-frontend/browser');
const PORT = Number(process.env.PWA_PORT) || 8082;
const HOST = process.env.PWA_HOST || '0.0.0.0';
const API_TARGET = process.env.PWA_API_TARGET || 'http://localhost:8080';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
};

function sendSpaIndex(res) {
  const indexPath = path.join(DIST, 'index.html');
  fs.readFile(indexPath, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Falta index.html. Ejecuta: ng build');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(data);
  });
}

function proxyApi(req, res) {
  const target = new URL(req.url, API_TARGET);
  const headers = { ...req.headers, host: target.host };

  const proxyReq = http.request(
    {
      hostname: target.hostname,
      port: target.port,
      path: target.pathname + target.search,
      method: req.method,
      headers,
    },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode, proxyRes.headers);
      proxyRes.pipe(res);
    }
  );

  proxyReq.on('error', () => {
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(
      `Backend no disponible en ${API_TARGET}.\n` +
        'Arranca Spring Boot (puerto 8080) y vuelve a intentar.'
    );
  });

  req.pipe(proxyReq);
}

function serveStatic(req, res) {
  const urlPath = decodeURIComponent(req.url.split('?')[0]);
  const safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(DIST, safePath === '/' ? 'index.html' : safePath);

  if (!filePath.startsWith(DIST)) {
    res.writeHead(403);
    res.end();
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (!err && stats.isFile()) {
      const ext = path.extname(filePath).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      fs.createReadStream(filePath).pipe(res);
      return;
    }
    sendSpaIndex(res);
  });
}

if (!fs.existsSync(DIST)) {
  console.error(`No existe ${DIST}. Ejecuta primero: ng build`);
  process.exit(1);
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api')) {
    proxyApi(req, res);
    return;
  }
  serveStatic(req, res);
});

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  QWERYS PWA — servidor de prueba');
  console.log(`  App:   http://localhost:${PORT}`);
  console.log(`  API:   ${API_TARGET} (proxy /api/**)`);
  console.log('  Backend Spring Boot debe estar en marcha.');
  console.log('');
});
