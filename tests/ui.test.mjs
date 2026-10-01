/**
 * Pruebas de integración de la interfaz (jsdom + fetch simulado).
 *
 *   npm install --save-dev jsdom && npm test
 *
 * Simula las cuatro APIs externas (GitHub, IMDb Suggest, TMDB, OMDb) y recorre
 * los flujos reales de la app: configuración, carga del watchlist, búsqueda,
 * alta manual, modal temporada/episodio, guardado en GitHub y errores.
 */
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(root, 'index.html'), 'utf8');
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const unb64 = (s) => Buffer.from(String(s).replace(/\s+/g, ''), 'base64').toString('utf8');

/* ------------------------------------------------------------------ */
/* 1 · Fixtures y simulación de la API                                */
/* ------------------------------------------------------------------ */

const REMOTE = [
  '# Peerflix Static · watchlist',
  '# comentario con acentos: acción, ñandú, ¡hola!',
  '',
  'tt0111161 The Shawshank Redemption (1994)',
  'tt0944947:s3 Game of Thrones Temporada 3',
  'tt0903747:s2:e5 Breaking Bad S02E05',
  'tt0133093'
].join('\n') + '\n';

const calls = [];
let putBody = null;
let forceStatus = null;          // fuerza un código de error en la siguiente petición GitHub

function json(status, data, statusText) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: statusText || (status === 200 ? 'OK' : status === 404 ? 'Not Found' : 'Error'),
    async text() { return JSON.stringify(data); }
  };
}
function raw(status, text) {
  return { ok: status >= 200 && status < 300, status, statusText: 'OK', async text() { return text; } };
}

function mockFetch(url, opts = {}) {
  const method = (opts.method || 'GET').toUpperCase();
  calls.push({ url: String(url), method, body: opts.body || null, headers: opts.headers || {} });
  if (forceStatus) {
    const st = forceStatus; forceStatus = null;
    return Promise.resolve(json(st, { message: st === 404 ? 'Not Found' : 'Conflict: sha mismatch' }));
  }

  /* --- GitHub --- */
  if (/api\.github\.com\/repos\/o\/r\/contents\//.test(url)) {
    if (method === 'PUT') {
      putBody = JSON.parse(opts.body);
      return Promise.resolve(json(200, {
        content: { sha: 'newsha9876543210', html_url: 'https://github.com/o/r/blob/main/watchlist.txt' },
        commit: { sha: 'commit1234567890', html_url: 'https://github.com/o/r/commit/commit123', message: putBody.message }
      }));
    }
    return Promise.resolve(json(200, {
      name: 'watchlist.txt', path: 'watchlist.txt', sha: 'abc123def4567890', size: Buffer.byteLength(REMOTE, 'utf8'),
      encoding: 'base64', content: b64(REMOTE), html_url: 'https://github.com/o/r/blob/main/watchlist.txt'
    }));
  }
  if (/api\.github\.com\/repos\/o\/r\/commits/.test(url)) {
    return Promise.resolve(json(200, [{
      sha: 'abc123def4567890',
      author: { login: 'noe359866' },
      commit: { author: { date: '2026-09-30T10:00:00Z', name: 'Noé' }, message: 'chore(watchlist): alta de temporada 3\n\ndetalle' },
      html_url: 'https://github.com/o/r/commit/abc123'
    }]));
  }
  if (/api\.github\.com\/rate_limit/.test(url)) {
    return Promise.resolve(json(200, { resources: { core: { limit: 5000, remaining: 4990, reset: Math.floor(Date.now() / 1000) + 1800 } } }));
  }
  if (/api\.github\.com\/user/.test(url)) return Promise.resolve(json(200, { login: 'noe359866', name: 'Noé' }));

  /* --- IMDb Suggest --- */
  if (/media-imdb\.com\/suggestion/.test(url)) {
    return Promise.resolve(json(200, {
      d: [
        { id: 'tt0133093', l: 'The Matrix', y: 1999, qid: 'movie', q: 'feature',
          i: { imageUrl: 'https://m.media-amazon.com/images/M/matrix.jpg' }, s: 'Keanu Reeves, Laurence Fishburne' },
        { id: 'tt0944947', l: 'Game of Thrones', y: 2011, qid: 'tvSeries', q: 'TV series',
          i: { imageUrl: 'https://m.media-amazon.com/images/M/got.jpg' } },
        { id: 'tt0234215', l: 'The Matrix Reloaded', y: 2003, qid: 'movie', q: 'feature' }
      ]
    }));
  }

  /* --- TMDB --- */
  if (/api\.themoviedb\.org\/3\/search\/multi/.test(url)) {
    return Promise.resolve(json(200, {
      results: [
        { media_type: 'movie', id: 603, title: 'The Matrix', release_date: '1999-03-31', original_language: 'en',
          vote_average: 8.2, poster_path: '/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg', overview: 'Un hacker descubre la verdad.' },
        { media_type: 'tv', id: 1399, name: 'Juego de tronos', first_air_date: '2011-04-17', original_language: 'es',
          vote_average: 8.4, poster_path: '/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg', overview: 'Nobles luchan por el trono.' }
      ]
    }));
  }
  if (/api\.themoviedb\.org\/3\/(movie|tv)\/(\d+)\/external_ids/.test(url)) {
    const kind = /\/tv\//.test(url) ? 'tv' : 'movie';
    const id = (/\/3\/(?:movie|tv)\/(\d+)\/external_ids/.exec(url) || [])[1];
    return Promise.resolve(json(200, { imdb_id: kind === 'tv' ? 'tt0944947' : (id === '999999' ? 'tt7777777' : 'tt0133093') }));
  }

  /* --- OMDb --- */
  if (/omdbapi\.com/.test(url)) {
    if (/[?&]i=tt/.test(url)) {
      const id = (/[?&]i=(tt\d+)/.exec(url) || [])[1];
      const table = {
        tt0944947: { Title: 'Game of Thrones', Year: '2011', Type: 'series', totalSeasons: '8', imdbID: 'tt0944947' },
        tt0903747: { Title: 'Breaking Bad', Year: '2008', Type: 'series', totalSeasons: '5', imdbID: 'tt0903747' },
        tt0133093: { Title: 'The Matrix', Year: '1999', Type: 'movie', imdbID: 'tt0133093' }
      };
      const row = table[id] || { Title: 'Desconocido', Year: '2000', Type: 'movie', imdbID: id };
      return Promise.resolve(json(200, Object.assign({ Response: 'True', Poster: 'https://m.media-amazon.com/images/M/' + id + '.jpg', Plot: 'Sinopsis.' }, row)));
    }
    return Promise.resolve(json(200, {
      Response: 'True',
      Search: [
        { Title: 'Breaking Bad', Year: '2008–2013', imdbID: 'tt0903747', Type: 'series', Poster: 'https://m.media-amazon.com/images/M/bb.jpg' },
        { Title: 'El camino', Year: '2019', imdbID: 'tt9243946', Type: 'movie', Poster: 'N/A' }
      ]
    }));
  }

  return Promise.resolve(json(500, { message: 'ruta no simulada: ' + url }));
}

/* ------------------------------------------------------------------ */
/* 2 · Entorno jsdom                                                  */
/* ------------------------------------------------------------------ */

const jsdomErrors = [];
const dom = new JSDOM(html, {
  url: 'https://peerflix.local/index.html',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = (url, opts) => mockFetch(url, opts);
    window.TextEncoder = TextEncoder;
    window.TextDecoder = TextDecoder;
    window.URL.createObjectURL = () => 'blob:mock';
    window.URL.revokeObjectURL = () => {};
    window.navigator.clipboard = { writeText: async () => {} };
    window.addEventListener('error', (e) => jsdomErrors.push(String((e && e.error && e.error.stack) || e.message || e)));
    window.alert = (m) => jsdomErrors.push('alert: ' + m);
    window.confirm = () => { jsdomErrors.push('confirm() nativo usado'); return false; };
  }
});
const { window } = dom;
const doc = window.document;
const $ = (sel) => doc.querySelector(sel);
const $$ = (sel) => Array.from(doc.querySelectorAll(sel));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(cond, label, timeout = 3000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    if (cond()) return true;
    await wait(15);
  }
  throw new Error('timeout esperando: ' + label);
}
const fire = (el, type) => el.dispatchEvent(new window.Event(type, { bubbles: true, cancelable: true }));

let pass = 0, fail = 0;
const t = (name, f) => {
  try {
    const r = f();
    if (r && typeof r.then === 'function') return r.then(
      () => { console.log('  ✓ ' + name); pass++; },
      (e) => { console.log('  ✗ ' + name + '\n      ' + e.message); fail++; }
    );
    console.log('  ✓ ' + name); pass++;
  } catch (e) {
    console.log('  ✗ ' + name + '\n      ' + e.message);
    fail++;
  }
};
const section = (s) => console.log('\n' + s);
const setVal = (sel, value) => { const el = $(sel); el.value = value; fire(el, 'input'); };
const previewText = () => $('#previewCode').textContent.replace(/\u00a0/g, ' ');
const previewLines = () => Array.from(doc.querySelectorAll('#previewCode .line'))
  .map((el) => el.textContent.replace(/^\s*\d+\s*/, ''))
  .map((l) => (l === '·' ? '' : l));   /* las líneas vacías muestran un glifo centinela */
const entryCards = () => $$('#list article[data-row]');

/* ------------------------------------------------------------------ */
/* 3 · Flujos                                                         */
/* ------------------------------------------------------------------ */

console.log('\nPeerflix Static · pruebas de interfaz (jsdom)');
console.log('fetch simulado · ' + (process.env.CI ? 'CI' : 'local'));

await wait(60);   // DOMContentLoaded + init()

section('1. Primer arranque');
await t('la app arranca sin errores de script', () => {
  if (jsdomErrors.length) throw new Error(jsdomErrors.join(' | '));
});
await t('expone el núcleo en window.PFX_CORE', () => {
  if (!window.PFX_CORE) throw new Error('PFX_CORE ausente');
});
await t('abre los ajustes al no haber configuración', () => {
  if ($('#modalSettings').classList.contains('hidden')) throw new Error('el modal de ajustes debería estar visible');
});
await t('la lista arranca vacía con estado vacío', () => {
  if (entryCards().length !== 0) throw new Error('había entradas inesperadas');
  if (!/#list|La lista está vacía/.test($('#list').textContent)) throw new Error('falta el mensaje de lista vacía');
});

section('2. Configuración de GitHub y APIs');
await t('guarda la configuración y persiste el token', async () => {
  setVal('#cfgOwner', 'o');
  setVal('#cfgRepo', 'r');
  setVal('#cfgBranch', 'main');
  setVal('#cfgPath', 'watchlist.txt');
  setVal('#cfgToken', 'github_pat_supersecreto');
  setVal('#cfgTmdb', 'clave-tmdb-32');
  setVal('#cfgOmdb', 'claveomdb');
  $('#cfgRemember').checked = true;
  $('#btnSaveCfg').click();
  await wait(30);
  const stored = JSON.parse(window.localStorage.getItem('peerflix.cfg.v1'));
  if (stored.owner !== 'o' || stored.repo !== 'r') throw new Error('config no persistida');
  if (stored.token !== 'github_pat_supersecreto') throw new Error('token no persistido con «recordar» activo');
  if ($('#modalSettings').classList.contains('hidden') === false) throw new Error('el modal no se cerró');
});

section('3. Carga de watchlist.txt desde GitHub');
await t('recarga automática y round-trip exacto del archivo', async () => {
  await waitFor(() => entryCards().length === 4, '4 entradas cargadas');
  if (previewLines().join('\n') + '\n' !== REMOTE) {
    throw new Error('el contenido cargado no coincide línea a línea:\n--- esperado ---\n' + JSON.stringify(REMOTE) +
      '\n--- obtenido ---\n' + JSON.stringify(previewLines().join('\n') + '\n'));
  }
  const get = calls.find((c) => c.method === 'GET' && /contents\/watchlist\.txt/.test(c.url));
  if (!get) throw new Error('no se llamó a la API de contenidos');
  if (!/ref=main/.test(get.url)) throw new Error('no se envió el parámetro ref');
});
await t('muestra el sha corto y el estado sincronizado', async () => {
  if (!/abc123d/.test($('#statusBadges').textContent)) throw new Error('falta el sha en la cabecera');
  if (!/Sincronizado/.test($('#statusBadges').textContent)) throw new Error('falta el badge Sincronizado');
  await waitFor(() => /noe359866/.test($('#remoteInfo').textContent), 'autor del último commit');
});
await t('clasifica entradas: temporada, episodio, comentarios y sin clasificar', () => {
  const stats = $('#wlStats').textContent;
  if (!/Comentarios: 2/.test(stats)) throw new Error('comentarios mal contados: ' + stats);
  if (!/Temporadas: 1/.test(stats) || !/Episodios: 1/.test(stats)) throw new Error('temporada/episodio mal contados: ' + stats);
  if (!/Sin clasificar: 2/.test(stats)) throw new Error('sin clasificar mal contado: ' + stats);
});
await t('completa títulos con OMDb sin reescribir las líneas que sólo traían ID', async () => {
  await waitFor(() => /The Matrix/.test($('#list').textContent), 'título completado por OMDb');
  const card = entryCards().find((c) => /tt0133093/.test(c.textContent));
  if (!card) throw new Error('no se encontró la tarjeta de tt0133093');
  if (!/The Matrix/.test(card.textContent)) throw new Error('OMDb no completó el título: ' + card.textContent);
  if (!/\(1999\)/.test(card.textContent)) throw new Error('OMDb no completó el año');
  const idx = previewLines().indexOf('tt0133093');
  if (idx === -1) throw new Error('la línea original se modificó: ' + JSON.stringify(previewLines()));
});

section('4. Buscador IMDb Suggest');
await t('busca y pinta resultados', async () => {
  setVal('#searchInput', 'matrix');
  fire($('#searchForm'), 'submit');
  await waitFor(() => $$('#results article').length === 3, '3 resultados de IMDb');
  const first = $$('#results article')[0];
  if (!/The Matrix/.test(first.textContent)) throw new Error('título inesperado');
  if (!/Película/.test(first.textContent)) throw new Error('falta el badge de tipo Película');
  if (!/tt0133093/.test(first.textContent)) throw new Error('falta el id IMDb');
});
await t('filtra por tipo «Series»', () => {
  $('#typeFilter button[data-type="series"]').click();
  const n = $$('#results article').length;
  if (n !== 1) throw new Error('se esperaban 1 serie, hay ' + n);
  $('#typeFilter button[data-type="all"]').click();
});
await t('marca los resultados que ya están en la lista', () => {
  const first = $$('#results article')[0];
  if (!/ya en la lista/.test(first.textContent)) throw new Error('no se marcó el resultado como presente');
});
await t('no duplica una entrada ya existente', async () => {
  $$('#results article')[0].querySelector('button[data-add]').click();
  await waitFor(() => /ya está en la lista/.test($('#toasts').textContent), 'aviso de duplicado');
  if (entryCards().length !== 4) throw new Error('se añadió un duplicado');
});
await t('añade otra película a la lista con un clic', async () => {
  $$('#results article')[2].querySelector('button[data-add]').click();
  await waitFor(() => entryCards().length === 5, 'entrada añadida');
  if (previewLines().indexOf('tt0234215 The Matrix Reloaded (2003)') === -1) {
    throw new Error('línea esperada ausente en la vista previa: ' + JSON.stringify(previewLines()));
  }
});

section('5. Modal temporada / episodio');
await t('abre el modal al pulsar una serie', async () => {
  $$('#results article')[1].querySelector('button[data-add]').click();
  await waitFor(() => !$('#modalPicker').classList.contains('hidden'), 'modal abierto');
  if (!/Game of Thrones/.test($('#pickTitle').textContent)) throw new Error('título incorrecto en el modal');
  await waitFor(() => $('#pickSeasonSelect').options.length === 8, 'temporadas detectadas por OMDb');
  if ($('#pickSeasonSelect').options.length !== 8) throw new Error('nº de temporadas incorrecto');
});
await t('por defecto añade la serie completa', () => {
  if (!/^tt0944947 Game of Thrones \(2011\)$/m.test($('#pickPreview').textContent)) {
    throw new Error('preview del modal: ' + $('#pickPreview').textContent);
  }
});
await t('modo temporada genera :sN', () => {
  $('#pickMode button[data-mode="season"]').click();
  setVal('#pickSeasonSelect', '4');
  if (!/tt0944947:s4 Game of Thrones Temporada 4/.test($('#pickPreview').textContent)) {
    throw new Error('preview: ' + $('#pickPreview').textContent);
  }
});
await t('modo episodios genera un rango :sN:eM', () => {
  $('#pickMode button[data-mode="episodes"]').click();
  setVal('#pickSeasonSelect', '2');
  setVal('#pickEpFrom', '3');
  setVal('#pickEpTo', '5');
  const txt = $('#pickPreview').textContent;
  if (!/tt0944947:s2:e3 Game of Thrones S02E03/.test(txt)) throw new Error('falta el episodio 3: ' + txt);
  if (!/tt0944947:s2:e5 Game of Thrones S02E05/.test(txt)) throw new Error('falta el episodio 5');
  if (/S02E06/.test(txt)) throw new Error('se pasó del rango');
  if (!/3 episodios/.test($('#pickEpInfo').textContent)) throw new Error('contador de episodios incorrecto');
});
await t('añade los 3 episodios y cierra el modal', async () => {
  $('#btnAddPick').click();
  await waitFor(() => $('#modalPicker').classList.contains('hidden'), 'modal cerrado');
  const txt = previewText();
  ['tt0944947:s2:e3 Game of Thrones S02E03', 'tt0944947:s2:e4 Game of Thrones S02E04', 'tt0944947:s2:e5 Game of Thrones S02E05']
    .forEach((l) => { if (txt.indexOf(l) === -1) throw new Error('falta «' + l + '»'); });
});
await t('omite duplicados al repetir el alta', async () => {
  const before = entryCards().length;
  $$('#results article')[1].querySelector('button[data-add]').click();
  await waitFor(() => !$('#modalPicker').classList.contains('hidden'), 'modal abierto');
  await waitFor(() => $('#pickSeasonSelect').options.length === 8 && !$('#pickSeasonSelect').classList.contains('hidden'), 'temporadas resueltas');
  $('#pickMode button[data-mode="episodes"]').click();
  setVal('#pickSeasonSelect', '2'); setVal('#pickEpFrom', '3'); setVal('#pickEpTo', '5');
  $('#btnAddPick').click();
  await wait(80);
  if (entryCards().length !== before) throw new Error('se añadieron duplicados (' + before + ' → ' + entryCards().length + ')');
  if (!/ya existían/.test($('#toasts').textContent)) throw new Error('no se avisó de los duplicados');
});

section('6. Alta manual (IDs, URLs y líneas completas)');
await t('añade ID suelto + comentario', async () => {
  const before = entryCards().length;
  setVal('#manualInput', '# nueva sección\ntt0068646 The Godfather (1972)');
  $('#btnManualAdd').click();
  await waitFor(() => entryCards().length === before + 1, 'entrada manual añadida');
  if (!/# nueva sección/.test(previewText())) throw new Error('no se conservó el comentario');
});
await t('resuelve un ID de TMDB desde su URL', async () => {
  const before = entryCards().length;
  setVal('#manualInput', 'https://www.themoviedb.org/movie/999999');
  $('#btnManualAdd').click();
  await waitFor(() => entryCards().length === before + 1, 'alta desde URL de TMDB');
  const txt = previewText();
  if (!/tt7777777/.test(txt)) throw new Error('no se resolvió el IMDb id de TMDB');
});
await t('rechaza líneas con formato inválido y las devuelve al cuadro', async () => {
  setVal('#manualInput', 'esto no es una entrada');
  $('#btnManualAdd').click();
  await waitFor(() => /No se reconocieron/.test($('#toasts').textContent), 'aviso de formato');
  if ($('#manualInput').value !== 'esto no es una entrada') throw new Error('no se devolvió la línea inválida');
  setVal('#manualInput', '');
});
await t('permite eliminar entradas de la lista', async () => {
  const before = entryCards().length;
  const card = entryCards().find((c) => /Matrix Reloaded/.test(c.textContent));
  card.querySelector('button[data-del]').click();
  await waitFor(() => entryCards().length === before - 1, 'entrada eliminada');
});

section('7. Guardado en GitHub');
await t('abre el diálogo de confirmación con el resumen del diff', async () => {
  $('#btnPush').click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'diálogo abierto');
  if (!/\+/.test($('#askBody').textContent)) throw new Error('no se muestra el número de líneas nuevas');
});
await t('envía el PUT con Base64 UTF-8 y el sha actual', async () => {
  $('#askOk').click();
  await waitFor(() => putBody !== null, 'PUT enviado');
  const sent = unb64(putBody.content);
  const expected = previewLines().join('\n') + '\n';
  if (sent !== expected) throw new Error('el PUT no coincide con la vista previa:\n--- enviado ---\n' + JSON.stringify(sent) + '\n--- preview ---\n' + JSON.stringify(expected));
  if (putBody.sha !== 'abc123def4567890') throw new Error('sha incorrecto: ' + putBody.sha);
  if (putBody.branch !== 'main') throw new Error('rama incorrecta');
  if (!/^chore\(watchlist\)/.test(putBody.message)) throw new Error('mensaje de commit inesperado: ' + putBody.message);
  ['acción', 'ñandú', 'Game of Thrones Temporada 3', 'tt0944947:s2:e5 Game of Thrones S02E05'].forEach((frag) => {
    if (sent.indexOf(frag) === -1) throw new Error('el contenido enviado perdió «' + frag + '»:\n' + sent);
  });
  const raw64 = String(putBody.content);
  if (/[+/=]/.test(raw64) === false && Buffer.byteLength(sent) > 0 && raw64.indexOf('\n') !== -1) throw new Error('base64 con saltos de línea');
});
await t('muestra confirmación con enlace al commit y estado limpio', async () => {
  await waitFor(() => /Guardado en GitHub/.test($('#toasts').textContent), 'toast de éxito');
  const link = Array.from($('#toasts').querySelectorAll('a')).find((a) => /Ver el commit/.test(a.textContent));
  if (!link || !/github\.com\/o\/r\/commit/.test(link.getAttribute('href'))) throw new Error('falta el enlace al commit');
  if (!/Sincronizado/.test($('#statusBadges').textContent)) throw new Error('el estado no quedó sincronizado');
  if (!/sin cambios/.test($('#diffBadges').textContent)) throw new Error('el diff no quedó limpio: ' + $('#diffBadges').textContent);
});
await t('avisa cuando no hay cambios que guardar', async () => {
  $('#btnPush2').click();
  await waitFor(() => /Sin cambios que guardar/.test($('#askTitle').textContent), 'diálogo «sin cambios»');
  $('#askCancel').click();
});

section('8. Manejo de errores');
await t('error 404 al leer el archivo', async () => {
  forceStatus = 404;
  $('#btnPull').click();
  await waitFor(() => /No se pudo cargar/.test($('#toasts').textContent), 'toast de error 404');
  if (!/404/.test($('#toasts').textContent)) throw new Error('no se menciona el 404');
});
await t('conflicto de sha (409) al guardar', async () => {
  const before = entryCards().length;
  setVal('#manualInput', 'tt9243946 El camino (2019)');
  $('#btnManualAdd').click();
  await waitFor(() => entryCards().length === before + 1, 'entrada añadida');
  forceStatus = 409;
  $('#btnPush').click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'diálogo abierto');
  $('#askOk').click();
  await waitFor(() => /Conflicto|409/.test($('#toasts').textContent), 'toast de conflicto');
  if (!/Recargar/.test($('#toasts').textContent)) throw new Error('no se sugiere recargar');
});
await t('los fallos de red se reportan con claridad', async () => {
  const original = window.fetch;
  window.fetch = () => Promise.reject(new TypeError('fetch failed'));
  setVal('#searchInput', 'matrix');
  fire($('#searchForm'), 'submit');
  await waitFor(() => /No se pudo conectar|bloqueado/.test($('#searchHint').textContent), 'aviso de red');
  window.fetch = original;
});

section('9. Persistencia local y utilidades');
await t('la vista previa se puede copiar al portapapeles', async () => {
  $('#btnCopyPreview').click();
  await waitFor(() => /copiado al portapapeles/i.test($('#toasts').textContent), 'toast de copia');
});
await t('guarda el borrador en localStorage', async () => {
  await waitFor(() => !!window.localStorage.getItem('peerflix.draft.v1'), 'autoguardado del borrador', 4000);
  const draft = window.localStorage.getItem('peerflix.draft.v1');
  if (!draft) throw new Error('sin borrador guardado');
  const data = JSON.parse(draft);
  if (!Array.isArray(data.seq) || !data.seq.length) throw new Error('borrador vacío');
});
await t('mantiene el orden original de las líneas', () => {
  const firsts = previewLines().map((l) => l.trim());
  if (firsts[0] !== '# Peerflix Static · watchlist') throw new Error('el orden se alteró: ' + firsts[0]);
});
await t('no hay errores de script acumulados en toda la sesión', () => {
  if (jsdomErrors.length) throw new Error(jsdomErrors.join(' | '));
});

section('10. Integridad del documento');
await t('no hay ids duplicados en el HTML', () => {
  const ids = Array.from(doc.querySelectorAll('[id]')).map((el) => el.id);
  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  if (dupes.length) throw new Error('ids repetidos: ' + [...new Set(dupes)].join(', '));
});
await t('todos los selectores #id del script existen en el DOM', () => {
  const script = Array.from(doc.querySelectorAll('script')).map((s) => s.textContent).join('\n');
  const ids = new Set(Array.from(script.matchAll(/\$\('#([A-Za-z0-9_-]+)/g)).map((m) => m[1]));
  const missing = [...ids].filter((id) => !doc.getElementById(id));
  if (missing.length) throw new Error('ids inexistentes: ' + missing.join(', '));
});
await t('el documento declara accesibilidad y metadatos básicos', () => {
  if (doc.documentElement.lang !== 'es') throw new Error('falta lang="es"');
  if (!doc.querySelector('meta[name="viewport"]')) throw new Error('falta viewport');
  if (doc.title.indexOf('Peerflix') === -1) throw new Error('title inesperado: ' + doc.title);
  const imgs = doc.querySelectorAll('#results img, #list img');
  imgs.forEach((img) => { if (!img.getAttribute('alt') && img.getAttribute('alt') !== '') throw new Error('img sin alt'); });
});
await t('los botones principales tienen etiqueta accesible', () => {
  const iconOnly = ['#btnSettings', '#btnCopyPreview', '#btnReset'];
  iconOnly.forEach((sel) => {
    const el = doc.querySelector(sel);
    if (!el) return;
    if (!el.getAttribute('aria-label') && !el.textContent.trim()) throw new Error(sel + ' sin aria-label');
  });
});

/* ------------------------------------------------------------------ */
console.log('\n' + (fail ? '✗' : '✓') + ' ' + pass + ' pruebas correctas, ' + fail + ' fallidas');
console.log('peticiones simuladas: ' + calls.length + '\n');
process.exit(fail ? 1 : 0);
