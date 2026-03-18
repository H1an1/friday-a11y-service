#!/usr/bin/env node
// Friday A11y Relay — lightweight command queue
// Mac side: accepts commands from agent, queues for phone to poll
// Phone side: A11y service polls /poll, posts results to /result

import http from 'node:http';

const PORT = 7334;
const AUTH_TOKEN = process.env.A11Y_RELAY_TOKEN || 'friday-a11y-relay';

let pendingCommand = null;
let pendingResolve = null;
let results = new Map();
let notificationCallbacks = []; // SSE or poll clients waiting for notifications
let recentNotifications = []; // Last 50 notifications from phone
const MAX_NOTIFICATIONS = 50;

const server = http.createServer((req, res) => {
  const auth = req.headers.authorization;
  if (auth !== `Bearer ${AUTH_TOKEN}`) {
    res.writeHead(401, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({error: 'unauthorized'}));
    return;
  }

  // Phone polls this
  if (req.method === 'GET' && req.url === '/poll') {
    if (pendingCommand) {
      const cmd = pendingCommand;
      pendingCommand = null;
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify(cmd));
    } else {
      // Long poll — wait up to 30s
      const timeout = setTimeout(() => {
        res.writeHead(204);
        res.end();
      }, 30000);
      
      pendingResolve = (cmd) => {
        clearTimeout(timeout);
        pendingCommand = null;
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end(JSON.stringify(cmd));
        pendingResolve = null;
      };
    }
    return;
  }

  // Phone posts results
  if (req.method === 'POST' && req.url === '/result') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const json = JSON.parse(body);
        const id = json.id;
        if (id && results.has(id)) {
          results.get(id)(json.result);
          results.delete(id);
        }
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({ok: true}));
      } catch(e) {
        res.writeHead(400, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: e.message}));
      }
    });
    return;
  }

  // Agent sends commands here
  if (req.method === 'POST' && req.url === '/command') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const cmd = JSON.parse(body);
        const id = `cmd_${Date.now()}_${Math.random().toString(36).slice(2,8)}`;
        cmd.id = id;
        
        // Set up result promise
        const resultPromise = new Promise((resolve) => {
          results.set(id, resolve);
          // Timeout after 15s
          setTimeout(() => {
            if (results.has(id)) {
              results.delete(id);
              resolve({error: 'timeout'});
            }
          }, 15000);
        });
        
        // Queue command for phone
        if (pendingResolve) {
          pendingResolve(cmd);
        } else {
          pendingCommand = cmd;
        }
        
        // Wait for result
        resultPromise.then(result => {
          res.writeHead(200, {'Content-Type': 'application/json'});
          res.end(JSON.stringify(result));
        });
      } catch(e) {
        res.writeHead(400, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: e.message}));
      }
    });
    return;
  }

  // Health check
  if (req.method === 'GET' && req.url === '/ping') {
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({status: 'ok', service: 'friday-a11y-relay', version: 2, pending: !!pendingCommand}));
    return;
  }

  // Phone pushes notifications here
  if (req.method === 'POST' && req.url === '/notification') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const json = JSON.parse(body);
        recentNotifications.unshift({...json, relayReceivedAt: Date.now()});
        while (recentNotifications.length > MAX_NOTIFICATIONS) recentNotifications.pop();
        // Wake any waiting poll clients
        for (const cb of notificationCallbacks) cb(json);
        notificationCallbacks = [];
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({ok: true}));
      } catch(e) {
        res.writeHead(400, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: e.message}));
      }
    });
    return;
  }

  // Agent reads recent notifications
  if (req.method === 'GET' && req.url.startsWith('/notifications')) {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const limit = parseInt(url.searchParams.get('limit') || '20');
    const pkg = url.searchParams.get('package');
    let filtered = pkg ? recentNotifications.filter(n => n.package === pkg) : recentNotifications;
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({notifications: filtered.slice(0, limit)}));
    return;
  }

  // Agent long-polls for next notification
  if (req.method === 'GET' && req.url === '/notifications/wait') {
    const timeout = setTimeout(() => {
      notificationCallbacks = notificationCallbacks.filter(cb => cb !== handler);
      res.writeHead(204);
      res.end();
    }, 30000);
    const handler = (notification) => {
      clearTimeout(timeout);
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify(notification));
    };
    notificationCallbacks.push(handler);
    return;
  }

  res.writeHead(404, {'Content-Type': 'application/json'});
  res.end(JSON.stringify({error: 'not_found'}));
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Friday A11y Relay listening on :${PORT}`);
});
