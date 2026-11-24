// Minimal static server for the preview:  node web/serve.js [port]
const http = require('http');
const fs = require('fs');
const path = require('path');
const root = __dirname;
const port = Number(process.argv[2] || 8080);
const types = { '.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.css':'text/css', '.png':'image/png', '.md':'text/plain; charset=utf-8', '.json':'application/json' };
http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/' || p === '') p = '/index.html';
  let file = path.join(root, p);
  if (!file.startsWith(root)) { res.writeHead(403); return res.end('nope'); }
  if (!fs.existsSync(file) && fs.existsSync(path.join(root, '..', p))) file = path.join(root, '..', p);
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(buf);
  });
}).listen(port, '0.0.0.0', () => console.log('dumbOS preview on http://0.0.0.0:' + port));
