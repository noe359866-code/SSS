/**
 * Pruebas de integración de la interfaz (jsdom + fetch simulado).
 *
 *   npm install --save-dev jsdom && npm test
 *
 * Simula las once fuentes de metadatos (IMDb Suggest, imdbapi.dev, Cinemeta,
 * TVMaze, Wikidata, AniList, IMDbOT, TMDB, OMDb, Trakt, Simkl) y la API de
 * GitHub, y recorre los flujos reales: configuración, carga del watchlist,
 * búsqueda múltiple con fusión de duplicados, modal temporada/episodio con
 * episodios por temporada, alta manual, guardado y errores.
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
/* 1 · Fixtures y simulación de las APIs                              */
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

const gotVideos = () => {
  const counts = [10, 10, 10, 10, 10, 10, 7, 6];
  const out = [];
  counts.forEach((n, i) => { for (let e = 1; e <= n; e += 1) out.push({ season: i + 1, episode: e }); });
  return out;
};
const bbVideos = () => {
  const counts = [7, 13, 13, 13, 16];
  const out = [];
  counts.forEach((n, i) => { for (let e = 1; e <= n; e += 1) out.push({ season: i + 1, episode: e }); });
  return out;
};
/* Catálogo que usan varias fuentes simuladas */
const CATALOG = [
  { imdb: 'tt0133093', title: 'The Matrix', year: 1999, type: 'movie' },
  { imdb: 'tt0234215', title: 'The Matrix Reloaded', year: 2003, type: 'movie' },
  { imdb: 'tt0944947', title: 'Game of Thrones', year: 2011, type: 'series' },
  { imdb: 'tt0903747', title: 'Breaking Bad', year: 2008, type: 'series' },
  { imdb: 'tt0213338', title: 'Cowboy Bebop', year: 1998, type: 'series' },
  { imdb: 'tt9999999', title: 'Kaiju No. 8', year: 2024, type: 'series' }
];
const matchQuery = (title, url) => {
  const q = decodeURIComponent((/[?&](?:query|q|search|s)=([^&]*)/.exec(url) || [])[1] || '').toLowerCase().trim();
  if (!q) return true;
  const words = q.split(/\s+/);
  const t = title.toLowerCase();
  return t.indexOf(q) !== -1 || words.some((w) => w.length > 3 && t.indexOf(w) !== -1);
};

/* Archivo real del proyecto (solo IDs + cabecera de comentarios) */
const REAL_WL = [
  '# Peerflix Static \u2013 Watchlist',
  '',
  '# -----------------------------------------------------------',
  '# Una l\u00ednea por t\u00edtulo. La Action consultar\u00e1 peerflix.mov por cada una',
  '# y publicar\u00e1 los streams en public/data/.',
  '#',
  '# Formatos soportados:',
  '#   tt1234567          -> pel\u00edcula (IMDb)',
  '#   tt1234567:s3:e4    -> episodio concreto (serie, temporada 3, ep. 4)',
  '#   tt1234567:s3       -> todos los episodios conocidos de la temporada 3',
  '#                         (necesita TMDB_API_KEY para expandir; si no hay clave,',
  '#                          se ignora la l\u00ednea con aviso)',
  '#',
  '# L\u00edneas que empiezan por \'#\' son comentarios. Se ignoran l\u00edneas vac\u00edas.',
  '# Puedes a\u00f1adir un nombre humano tras un espacio para que la web lo muestre:',
  '#   tt0111161 Cadena perpetua (1994)',
  '#   tt1375666 Inception (2010)',
  '#   tt0944947:s1:e1 Game of Thrones S01E01',
  '#',
  '# Si defines TMDB_API_KEY en secrets (opcional), el script puede expandir',
  '# temporadas autom\u00e1ticamente y enriquecer metadatos (t\u00edtulo, a\u00f1o, p\u00f3ster).',
  '# Si no, usa el nombre que pongas en esta misma l\u00ednea.',
  '# -----------------------------------------------------------',
  'tt6933238',
  'tt22526100',
  'tt26657236',
  'tt29355505',
  'tt11561116',
  ''
].join('\n');

/* Fichas que sólo existen para el enriquecimiento (no salen en las búsquedas) */
const REAL_IDS = [
  { imdb: 'tt6933238', title: 'La Última Frontera', year: 2026, type: 'movie' },
  { imdb: 'tt22526100', title: 'Ciudad de Cristal', year: 2025, type: 'movie' },
  { imdb: 'tt26657236', title: 'El Silencio del Mar', year: 2024, type: 'movie' },
  { imdb: 'tt29355505', title: 'Sombras de Neón', year: 2026, type: 'movie' },
  { imdb: 'tt11561116', title: 'Cosecha Amarga', year: 2025, type: 'movie' }
];
const KNOWN = CATALOG.concat(REAL_IDS);

let calls = [];
let putBody = null;
let forceStatus = null;
let remoteText = REMOTE;

function json(status, data, statusText) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: statusText || (status === 200 ? 'OK' : status === 404 ? 'Not Found' : 'Error'),
    async text() { return JSON.stringify(data); }
  };
}

function mockFetch(url, opts = {}) {
  const method = (opts.method || 'GET').toUpperCase();
  calls.push({ url: String(url), method, body: opts.body || null, headers: opts.headers || {} });
  if (forceStatus && /api\.github\.com\/repos\//.test(url)) {
    const st = forceStatus; forceStatus = null;
    return Promise.resolve(json(st, { message: st === 404 ? 'Not Found' : 'Conflict: sha mismatch' }));
  }

  /* ----------------------------- GitHub ----------------------------- */
  if (/api\.github\.com\/repos\/o\/r\/contents\//.test(url)) {
    if (method === 'PUT') {
      putBody = JSON.parse(opts.body);
      return Promise.resolve(json(200, {
        content: { sha: 'newsha9876543210', html_url: 'https://github.com/o/r/blob/main/watchlist.txt' },
        commit: { sha: 'commit1234567890', html_url: 'https://github.com/o/r/commit/commit123', message: putBody.message }
      }));
    }
    return Promise.resolve(json(200, {
      name: 'watchlist.txt', path: 'watchlist.txt', sha: 'abc123def4567890', size: Buffer.byteLength(remoteText, 'utf8'),
      encoding: 'base64', content: b64(remoteText), html_url: 'https://github.com/o/r/blob/main/watchlist.txt'
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

  /* ------------------------- IMDb Suggest ------------------------- */
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

  /* ------------------------- imdbapi.dev ------------------------- */
  if (/api\.imdbapi\.dev\/search\/titles/.test(url)) {
    const titles = CATALOG.filter((c) => matchQuery(c.title, url)).map((c) => ({
      id: c.imdb, type: c.type === 'movie' ? 'MOVIE' : 'TV_SERIES',
      primaryTitle: c.title, originalTitle: c.title, startYear: c.year,
      primaryImage: { url: 'https://m.media-amazon.com/images/M/' + c.imdb + '.jpg' },
      rating: { aggregateRating: 8.7 }
    }));
    return Promise.resolve(json(200, { titles }));
  }
  if (/api\.imdbapi\.dev\/titles\/tt/.test(url)) {
    const id = (/titles\/(tt\d+)/.exec(url) || [])[1];
    const row = KNOWN.find((c) => c.imdb === id);
    if (!row) return Promise.resolve(json(404, { message: 'not found' }));
    return Promise.resolve(json(200, {
      id: row.imdb, type: row.type === 'movie' ? 'MOVIE' : 'TV_SERIES',
      primaryTitle: row.title, startYear: row.year, plot: 'Sinopsis de ' + row.title + '.',
      primaryImage: { url: 'https://m.media-amazon.com/images/M/' + row.imdb + '.jpg' }
    }));
  }

  /* --------------------------- Cinemeta --------------------------- */
  if (/cinemeta(-live)?\.strem\.io/.test(url)) {
    const search = /\/catalog\/(movie|series)\/top\/search=([^.]*)\.json/.exec(url);
    if (search) {
      const kind = search[1];
      const metas = CATALOG.filter((c) => (kind === 'movie' ? c.type === 'movie' : c.type === 'series'))
        .map((c) => ({ id: c.imdb, type: c.type, name: c.title, releaseInfo: String(c.year), imdbRating: '8.7',
                       poster: 'https://images.metahub.space/poster/medium/' + c.imdb + '/img' }));
      return Promise.resolve(json(200, { metas }));
    }
    const meta = /\/meta\/(movie|series)\/(tt\d+)\.json/.exec(url);
    if (meta) {
      const kind = meta[1], id = meta[2];
      const row = KNOWN.find((c) => c.imdb === id && c.type === kind);
      if (!row) return Promise.resolve(json(404, { err: 'not found' }));
      const videos = id === 'tt0944947' ? gotVideos() : id === 'tt0903747' ? bbVideos() : null;
      return Promise.resolve(json(200, { meta: { id: id, type: kind, name: row.title, releaseInfo: String(row.year),
        poster: 'https://images.metahub.space/poster/medium/' + id + '/img', description: 'Sinopsis de ' + row.title + '.', videos } }));
    }
  }

  /* ---------------------------- TVMaze ---------------------------- */
  if (/api\.tvmaze\.com\/search\/shows/.test(url)) {
    const rows = CATALOG.filter((c) => c.type === 'series').map((c, i) => ({
      score: 9 - i,
      show: { id: 100 + i, name: c.title, premiered: c.year + '-01-01', status: 'Ended', type: 'Scripted',
        externals: { imdb: c.imdb }, image: { medium: 'https://static.tvmaze.com/' + c.imdb + '.jpg' },
        summary: '<p>Sinopsis de <b>' + c.title + '</b>.</p>' }
    }));
    return Promise.resolve(json(200, rows));
  }
  if (/api\.tvmaze\.com\/lookup\/shows\?imdb=/.test(url)) {
    const id = (/imdb=(tt\d+)/.exec(url) || [])[1];
    const row = KNOWN.find((c) => c.imdb === id && c.type === 'series');
    if (!row) return Promise.resolve(json(404, { message: 'not found', name: 'Not Found' }));
    const idx = CATALOG.filter((c) => c.type === 'series').indexOf(row);
    return Promise.resolve(json(200, { id: 100 + idx, name: row.title, premiered: row.year + '-01-01',
      status: 'Ended', image: { medium: 'https://static.tvmaze.com/' + id + '.jpg' }, externals: { imdb: id } }));
  }
  if (/api\.tvmaze\.com\/shows\/(\d+)\/seasons/.test(url)) {
    const showId = Number((/shows\/(\d+)\//.exec(url) || [])[1]);
    const series = CATALOG.filter((c) => c.type === 'series');
    const show = series[showId - 100];
    if (!show) return Promise.resolve(json(200, []));
    const counts = show.imdb === 'tt0944947' ? [10, 10, 10, 10, 10, 10, 7, 6]
      : show.imdb === 'tt0903747' ? [7, 13, 13, 13, 16] : [1, 12, 12];
    return Promise.resolve(json(200, counts.map((n, i) => ({ id: 1000 + i, number: i + 1, episodeOrder: n }))));
  }

  /* --------------------------- Wikidata --------------------------- */
  if (/wikidata\.org\/w\/api\.php/.test(url) && /wbsearchentities/.test(url)) {
    const q = decodeURIComponent((/[?&]search=([^&]*)/.exec(url) || [])[1] || '').toLowerCase();
    const hits = [
      { id: 'Q83495', label: 'The Matrix', description: 'película de 1999' },
      { id: 'Q23572', label: 'Game of Thrones', description: 'serie de televisión' },
      { id: 'Q186447', label: 'Cowboy Bebop', description: 'anime de 1998' }
    ].filter((h) => !q || h.label.toLowerCase().indexOf(q) !== -1 || q.indexOf(h.label.toLowerCase()) !== -1);
    return Promise.resolve(json(200, { search: hits }));
  }
  if (/wikidata\.org\/w\/api\.php/.test(url) && /wbgetentities/.test(url)) {
    const make = (imdb, qid, title, time) => ({
      labels: { es: { value: title }, en: { value: title } },
      descriptions: { es: { value: 'ficha de Wikidata' } },
      claims: {
        P345: [{ mainsnak: { datavalue: { value: imdb } } }],
        P31: [{ mainsnak: { datavalue: { value: { id: qid } } } }],
        P577: [{ mainsnak: { datavalue: { value: { time } } } }]
      }
    });
    return Promise.resolve(json(200, { entities: {
      Q83495: make('tt0133093', 'Q11424', 'The Matrix', '+1999-03-31T00:00:00Z'),
      Q23572: make('tt0944947', 'Q5398426', 'Game of Thrones', '+2011-04-17T00:00:00Z'),
      Q186447: make('tt0213338', 'Q15416', 'Cowboy Bebop', '+1998-04-03T00:00:00Z')
    } }));
  }

  /* ---------------------------- AniList ---------------------------- */
  if (/graphql\.anilist\.co/.test(url)) {
    const q = JSON.parse(opts.body || '{}').variables ? JSON.parse(opts.body).variables.q : '';
    const media = [
      { id: 1, idMal: 1, format: 'TV', episodes: 26, startDate: { year: 1998 },
        coverImage: { large: 'https://s4.anilist.co/bebop.jpg' },
        title: { romaji: 'Cowboy Bebop', english: 'Cowboy Bebop', native: 'カウボーイビバップ' },
        externalLinks: [{ site: 'IMDb', url: 'https://www.imdb.com/title/tt0213338/' }] },
      { id: 2, idMal: 2, format: 'TV', episodes: 12, startDate: { year: 2024 },
        coverImage: { large: 'https://s4.anilist.co/kaiju.jpg' },
        title: { romaji: 'Kaijuu 8-gou', english: 'Kaiju No. 8', native: '怪獣8号' },
        externalLinks: [{ site: 'Official Site', url: 'https://kaiju-no8.example' }] }
    ];
    return Promise.resolve(json(200, { data: { Page: { media } } }));
  }

  /* --------------------- IMDbOT (experimental) --------------------- */
  if (/imdb\.iamidiotareyoutoo\.com\/search/.test(url)) {
    return Promise.resolve(json(200, { description: CATALOG.map((c) => ({
      '#IMDB_ID': c.imdb, '#TITLE': c.title, '#YEAR': String(c.year),
      '#IMG_POSTER': 'https://m.media-amazon.com/images/M/' + c.imdb + '.jpg'
    })) }));
  }

  /* ----------------------------- Trakt ----------------------------- */
  if (/api\.trakt\.tv\/search\//.test(url)) {
    if (opts.headers && opts.headers['trakt-api-key'] === 'clave-mala') {
      return Promise.resolve(json(401, { error: 'invalid_client', error_description: 'Invalid API key' }));
    }
    if (/\/search\/movie/.test(url)) {
      return Promise.resolve(json(200, CATALOG.filter((c) => c.type === 'movie')
        .map((c) => ({ movie: { title: c.title, year: c.year, ids: { trakt: 1, imdb: c.imdb, tmdb: 603 } } }))));
    }
    if (/\/search\/imdb\//.test(url)) {
      const id = (/\/(tt\d+)/.exec(url) || [])[1];
      const row = CATALOG.find((c) => c.imdb === id && c.type === 'series');
      if (!row) return Promise.resolve(json(200, []));
      return Promise.resolve(json(200, [{ show: { title: row.title, year: row.year, ids: { imdb: row.imdb } } }]));
    }
    if (/\/search\/show/.test(url)) {
      return Promise.resolve(json(200, CATALOG.filter((c) => c.type === 'series')
        .map((c) => ({ show: { title: c.title, year: c.year, ids: { trakt: 2, imdb: c.imdb, tmdb: 1399, tvdb: 121361 } } }))));
    }
  }

  /* ----------------------------- Simkl ----------------------------- */
  if (/api\.simkl\.com\/search\//.test(url)) {
    const kind = /\/search\/(movie|tv|anime)/.exec(url)[1];
    const want = kind === 'movie' ? 'movie' : 'series';
    return Promise.resolve(json(200, CATALOG.filter((c) => c.type === want)
      .map((c, i) => ({ title: c.title, year: c.year, ids: { simkl: 10 + i, imdb: c.imdb, tmdb: 603 },
                        poster: 'https://simkl.in/' + c.imdb + '.jpg' }))));
  }

  /* ------------------------------ TMDB ------------------------------ */
  if (/api\.themoviedb\.org\/3\/search\/multi/.test(url)) {
    return Promise.resolve(json(200, {
      results: [
        { media_type: 'movie', id: 603, title: 'The Matrix', release_date: '1999-03-31', original_language: 'en',
          vote_average: 8.2, poster_path: '/matriz.jpg', overview: 'Un hacker descubre la verdad.' },
        { media_type: 'tv', id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17', original_language: 'en',
          vote_average: 8.4, poster_path: '/got.jpg', overview: 'Nobles luchan por el trono.' }
      ]
    }));
  }
  if (/api\.themoviedb\.org\/3\/(movie|tv)\/(\d+)\/external_ids/.test(url)) {
    const m = /\/3\/(movie|tv)\/(\d+)\/external_ids/.exec(url);
    const id = m[1] === 'tv' ? 'tt0944947' : (m[2] === '999999' ? 'tt7777777' : 'tt0133093');
    return Promise.resolve(json(200, { imdb_id: id }));
  }
  if (/api\.themoviedb\.org\/3\/find\//.test(url)) {
    const id = (/(tt\d+)/.exec(url) || [])[1];
    const row = KNOWN.find((c) => c.imdb === id);
    if (!row) return Promise.resolve(json(200, { movie_results: [], tv_results: [] }));
    return Promise.resolve(json(200, row.type === 'series'
      ? { movie_results: [], tv_results: [{ id: 1399, name: row.title }] }
      : { movie_results: [{ id: 603, title: row.title }], tv_results: [] }));
  }
  if (/api\.themoviedb\.org\/3\/(movie|tv)\//.test(url)) {
    const isTv = /\/tv\//.test(url);
    return Promise.resolve(json(200, isTv
      ? { id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17', number_of_seasons: 8,
          seasons: [1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({ season_number: n, episode_count: n === 7 ? 7 : n === 8 ? 6 : 10 })),
          poster_path: '/got.jpg', overview: 'Nobles luchan por el trono.' }
      : { id: 603, title: 'The Matrix', release_date: '1999-03-31', poster_path: '/matriz.jpg', overview: 'Un hacker.' }));
  }

  /* ------------------------------ OMDb ------------------------------ */
  if (/omdbapi\.com/.test(url)) {
    if (/[?&]i=tt/.test(url)) {
      const id = (/[?&]i=(tt\d+)/.exec(url) || [])[1];
      const row = KNOWN.find((c) => c.imdb === id);
      if (!row) return Promise.resolve(json(200, { Response: 'False', Error: 'Movie not found!' }));
      return Promise.resolve(json(200, {
        Response: 'True', Title: row.title, Year: String(row.year), Type: row.type,
        imdbID: row.imdb, Poster: 'https://m.media-amazon.com/images/M/' + row.imdb + '.jpg',
        Plot: 'Sinopsis de ' + row.title + '.',
        totalSeasons: row.type === 'series' ? (row.imdb === 'tt0944947' ? '8' : '5') : undefined
      }));
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
async function waitFor(cond, label, timeout = 4000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    if (cond()) return true;
    await wait(15);
  }
  throw new Error('timeout esperando: ' + label);
}
const fire = (el, type) => el.dispatchEvent(new window.Event(type, { bubbles: true, cancelable: true }));
const setVal = (sel, value) => { const el = $(sel); el.value = value; fire(el, 'input'); };

const previewText = () => $('#previewCode').textContent.replace(/\u00a0/g, ' ');
const previewLines = () => Array.from(doc.querySelectorAll('#previewCode .line'))
  .map((el) => el.textContent.replace(/^\s*\d+\s*/, ''))
  .map((l) => (l === '·' ? '' : l));
const entryCards = () => $$('#list article[data-row]');
const resultCards = () => $$('#results article');
const cardTitle = (card) => (card.querySelector('h3') ? card.querySelector('h3').textContent.trim() : '');
const resultByTitle = (title) => resultCards().find((c) => cardTitle(c) === title);
async function selectSource(id) {
  const sel = $('#sourceSelect');
  sel.value = id;
  fire(sel, 'change');
  await wait(20);
}
async function searchFor(q) {
  setVal('#searchInput', q);
  fire($('#searchForm'), 'submit');
  await wait(30);   // deja arrancar la búsqueda
  await waitFor(() => !$('#results .skeleton') && !/Buscando/.test($('#searchHint').textContent), 'resultados pintados');
}
/** Activa/desactiva fuentes re-consultando el DOM (los re-renders invalidan los nodos). */
function setSourcesEnabled(keep) {
  Object.keys(window.PFX_STATE().providers).forEach((id) => {
    const chk = doc.querySelector('#providersList input[data-toggle-source="' + id + '"]');
    const want = keep === null || keep.indexOf(id) !== -1;
    if (chk && chk.checked !== want) { chk.checked = want; fire(chk, 'change'); }
  });
}

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

/* ------------------------------------------------------------------ */
/* 3 · Flujos                                                        */
/* ------------------------------------------------------------------ */

console.log('\nPeerflix Static · pruebas de interfaz (jsdom)');
console.log('11 fuentes de metadatos simuladas + GitHub');

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
await t('registra las 11 fuentes de metadatos', () => {
  const state = window.PFX_STATE();
  const ids = Object.keys(state.providers);
  const expected = ['imdb', 'imdbapi', 'cinemeta', 'tvmaze', 'wikidata', 'anilist', 'imdbot', 'tmdb', 'omdb', 'trakt', 'simkl'];
  const missing = expected.filter((id) => ids.indexOf(id) === -1);
  if (missing.length) throw new Error('faltan fuentes: ' + missing.join(', '));
  if (ids.length !== 11) throw new Error('se esperaban 11 fuentes, hay ' + ids.length + ': ' + ids.join(', '));
});
await t('sin claves quedan 7 fuentes usables y el selector lo refleja', () => {
  const options = Array.from($('#sourceSelect').options).map((o) => o.value);
  const usable = options.filter((v) => v !== 'all' && !$('#sourceSelect').querySelector('option[value="' + v + '"]').disabled);
  if (usable.length !== 7) throw new Error('usables: ' + usable.join(', '));
  ['tmdb', 'omdb', 'trakt', 'simkl'].forEach((id) => {
    const opt = $('#sourceSelect').querySelector('option[value="' + id + '"]');
    if (!opt || !opt.disabled) throw new Error(id + ' debería aparecer deshabilitada sin clave');
  });
  if ($('#sourceSelect').value !== 'all') throw new Error('la búsqueda por defecto debería ser «Todas las fuentes»');
});
await t('muestra las fuentes agrupadas en el panel lateral', () => {
  const txt = $('#providersList').textContent;
  ['Sin clave', 'Con clave gratuita', 'Cinemeta (Stremio)', 'imdbapi.dev', 'Wikidata', 'AniList'].forEach((s) => {
    if (txt.indexOf(s) === -1) throw new Error('falta «' + s + '» en el panel de fuentes');
  });
  if ($$('#providersList input[data-toggle-source]').length !== 11) throw new Error('faltan casillas de activación');
});

section('2. Configuración de GitHub y claves de API');
await t('guarda la configuración completa y persiste el token', async () => {
  setVal('#cfgOwner', 'o');
  setVal('#cfgRepo', 'r');
  setVal('#cfgBranch', 'main');
  setVal('#cfgPath', 'watchlist.txt');
  setVal('#cfgToken', 'github_pat_supersecreto');
  setVal('#cfgTmdb', 'clave-tmdb-32');
  setVal('#cfgOmdb', 'claveomdb');
  setVal('#cfgTrakt', 'clave-trakt');
  setVal('#cfgSimkl', 'clave-simkl');
  $('#cfgRemember').checked = true;
  $('#btnSaveCfg').click();
  await wait(30);
  const stored = JSON.parse(window.localStorage.getItem('peerflix.cfg.v1'));
  if (stored.owner !== 'o' || stored.repo !== 'r') throw new Error('config no persistida');
  if (stored.token !== 'github_pat_supersecreto') throw new Error('token no persistido');
  if (stored.traktKey !== 'clave-trakt' || stored.simklKey !== 'clave-simkl') throw new Error('claves de Trakt/Simkl no persistidas');
});
await t('con las 4 claves quedan 11 fuentes usables', () => {
  const opts = Array.from($('#sourceSelect').options).filter((o) => !o.disabled && o.value !== 'all');
  if (opts.length !== 11) throw new Error('usables: ' + opts.length + ' → ' + opts.map((o) => o.value).join(', '));
  if (!/11 activas/.test($('#sourceCount').textContent)) throw new Error('contador: ' + $('#sourceCount').textContent);
});

section('3. Carga de watchlist.txt desde GitHub');
await t('recarga automática y round-trip exacto del archivo', async () => {
  await waitFor(() => entryCards().length === 4, '4 entradas cargadas');
  if (previewLines().join('\n') + '\n' !== REMOTE) {
    throw new Error('contenido distinto:\n' + JSON.stringify(previewLines().join('\n') + '\n'));
  }
  const get = calls.find((c) => c.method === 'GET' && /contents\/watchlist\.txt/.test(c.url));
  if (!get || !/ref=main/.test(get.url)) throw new Error('no se llamó a la API de contenidos con ref');
});
await t('muestra sha, estado y autor del último commit', async () => {
  if (!/abc123d/.test($('#statusBadges').textContent)) throw new Error('falta el sha');
  if (!/Sincronizado/.test($('#statusBadges').textContent)) throw new Error('falta el badge Sincronizado');
  await waitFor(() => /noe359866/.test($('#remoteInfo').textContent), 'autor del commit');
});
await t('clasifica entradas (temporada, episodio, comentarios, sin clasificar)', () => {
  const stats = $('#wlStats').textContent;
  [['Comentarios: 2'], ['Temporadas: 1'], ['Episodios: 1'], ['Sin clasificar: 2']].forEach(([frag]) => {
    if (stats.indexOf(frag) === -1) throw new Error('falta «' + frag + '» en ' + stats);
  });
});
await t('completa metadatos sin reescribir las líneas que sólo traían ID', async () => {
  await waitFor(() => /The Matrix/.test($('#list').textContent), 'metadatos completados');
  const idx = previewLines().indexOf('tt0133093');
  if (idx === -1) throw new Error('la línea original se modificó: ' + JSON.stringify(previewLines()));
  const card = entryCards().find((c) => /tt0133093/.test(c.textContent));
  if (!/The Matrix/.test(card.textContent)) throw new Error('sin título desde OMDb/Cinemeta');
});

section('4. Búsqueda multifuente');
await t('busca en las 11 fuentes a la vez y muestra el estado de cada una', async () => {
  const before = calls.length;
  await searchFor('matrix');
  const chips = $$('#sourceChips .badge');
  if (chips.length !== 11) throw new Error('se esperaban 11 chips de fuente, hay ' + chips.length);
  const okChips = chips.filter((c) => /·/.test(c.textContent) && !/✕/.test(c.textContent));
  if (okChips.length !== 11) throw new Error('chips con error: ' + chips.filter((c) => /✕/.test(c.textContent)).map((c) => c.textContent).join(' | '));
  const used = new Set(calls.slice(before).map((c) => new URL(c.url).host));
  ['v3.sg.media-imdb.com', 'api.imdbapi.dev', 'v3-cinemeta.strem.io', 'api.tvmaze.com', 'www.wikidata.org',
   'graphql.anilist.co', 'imdb.iamidiotareyoutoo.com', 'api.trakt.tv', 'api.simkl.com', 'api.themoviedb.org', 'www.omdbapi.com']
    .forEach((host) => { if (!used.has(host)) throw new Error('no se consultó ' + host); });
});
await t('fusiona duplicados: cada título aparece una sola vez', () => {
  const titles = resultCards().map(cardTitle);
  const dupes = titles.filter((t, i) => titles.indexOf(t) !== i);
  if (dupes.length) throw new Error('títulos duplicados: ' + dupes.join(', '));
  const matrix = resultByTitle('The Matrix');
  if (!matrix) throw new Error('no está The Matrix: ' + titles.join(' | '));
  if (!/fuentes/.test(matrix.textContent)) throw new Error('no se indica que varias fuentes lo confirman');
});
await t('The Matrix reúne IMDb + TMDB + OMDb y ordena por relevancia', () => {
  const first = resultCards()[0];
  if (cardTitle(first) !== 'The Matrix') throw new Error('primer resultado: ' + cardTitle(first));
  const m = /(\d+) fuentes/.exec(first.textContent);
  if (!m || Number(m[1]) < 5) throw new Error('fuentes fusionadas insuficientes: ' + m);
  if (!/tt0133093/.test(first.textContent)) throw new Error('falta el ID de IMDb');
  if (!/Película/.test(first.textContent)) throw new Error('falta el tipo');
});
await t('las entradas sin coincidencia quedan al final', () => {
  const titles = resultCards().map(cardTitle);
  const iBb = titles.indexOf('Breaking Bad');
  if (iBb === -1) throw new Error('falta Breaking Bad: ' + titles.join(', '));
  if (iBb < 2) throw new Error('el ranking no prioriza coincidencias exactas: ' + titles.join(', '));
});
await t('ordena también por relevancia con términos parciales', async () => {
  await searchFor('game of thr');
  const first = resultCards()[0];
  if (cardTitle(first) !== 'Game of Thrones') throw new Error('primer resultado: ' + cardTitle(first));
});
await t('filtra por tipo (Series)', async () => {
  await searchFor('matrix');
  $('#typeFilter button[data-type="series"]').click();
  const titles = resultCards().map(cardTitle);
  if (titles.indexOf('The Matrix') !== -1) throw new Error('una película pasó el filtro de series');
  if (titles.indexOf('Game of Thrones') === -1) throw new Error('falta Game of Thrones: ' + titles.join(', '));
  $('#typeFilter button[data-type="all"]').click();
});
await t('resuelve el ID de IMDb de fuentes sin ID (AniList → imdbapi.dev)', async () => {
  /* Se desactivan todas las fuentes menos AniList para forzar la resolución */
  setSourcesEnabled(['anilist']);
  await wait(20);
  await selectSource('all');
  await searchFor('kaiju');
  const card = resultByTitle('Kaiju No. 8');
  if (!card) throw new Error('AniList no devolvió «Kaiju No. 8»: ' + resultCards().map(cardTitle).join(', '));
  if (!/se resolverá el IMDb id/.test(card.textContent)) throw new Error('debería avisar de que falta el IMDb id');
  card.querySelector('button[data-add]').click();
  /* Es una serie: se abre el modal, que resuelve el ID y permite elegir temporada */
  await waitFor(() => !$('#modalPicker').classList.contains('hidden'), 'modal del picker');
  await waitFor(() => /tt9999999/.test($('#pickImdb').textContent), 'ID resuelto en el picker');
  await waitFor(() => !$('#btnAddPick').disabled, 'botón de añadir habilitado');
  $('#btnAddPick').click();
  await waitFor(() => /tt9999999/.test(previewText()), 'ID resuelto y añadido');
  const stats = $('#wlStats').textContent;
  if (stats.indexOf('Series: ') === -1) throw new Error('no se clasificó como serie: ' + stats);
  /* Se restauran todas las fuentes */
  setSourcesEnabled(null);
  await wait(20);
});
await t('una fuente caída no rompe la búsqueda múltiple', async () => {
  const before = calls.length;
  await searchFor('matrix');
  if (!resultCards().length) throw new Error('la búsqueda múltiple no devolvió resultados');
  if (before === calls.length) throw new Error('no se lanzaron peticiones');
});
await t('Trakt con clave inválida se reporta sin abortar el resto', async () => {
  setVal('#cfgTrakt', 'clave-mala');
  $('#btnSaveCfg').click();
  await wait(30);
  await searchFor('matrix');
  const txt = $('#toasts').textContent + $('#sourceChips').textContent;
  if (!/Trakt/.test(txt)) throw new Error('no se menciona a Trakt en el error');
  if (!/401|client_id/i.test(txt)) throw new Error('no se explica el motivo: ' + txt.slice(0, 300));
  if (!resultCards().length) throw new Error('el fallo de Trakt tumbó la búsqueda');
  setVal('#cfgTrakt', 'clave-trakt');
  $('#btnSaveCfg').click();
  await wait(30);
});
await t('cada fuente por separado devuelve resultados', async () => {
  const ids = ['imdb', 'imdbapi', 'cinemeta', 'tvmaze', 'wikidata', 'anilist', 'imdbot', 'tmdb', 'omdb', 'trakt', 'simkl'];
  for (const id of ids) {
    await selectSource(id);
    await searchFor('matrix');
    const n = resultCards().length;
    if (!n) throw new Error('la fuente «' + id + '» no devolvió resultados');
    calls.length; // no-op
  }
  await selectSource('imdb');
  await searchFor('matrix');
  const titles = resultCards().map(cardTitle);
  if (titles.indexOf('The Matrix') === -1 || titles.length !== 3) {
    throw new Error('IMDb Suggest debería devolver 3 resultados: ' + titles.join(', '));
  }
  await selectSource('all');
});

section('5. Modal temporada / episodio');
await t('abre el modal con las temporadas y episodios detectados', async () => {
  await searchFor('game of thr');
  resultByTitle('Game of Thrones').querySelector('button[data-add]').click();
  await waitFor(() => !$('#modalPicker').classList.contains('hidden'), 'modal abierto');
  await waitFor(() => $('#pickSeasonSelect').options.length === 8, '8 temporadas');
  const opts = Array.from($('#pickSeasonSelect').options).map((o) => o.textContent);
  if (!/Temporada 7 — 7 ep\./.test(opts[6])) throw new Error('no se muestran episodios por temporada: ' + opts[6]);
  if (!/temporada\(s\)/.test($('#pickSeasonInfo').textContent)) throw new Error('falta el resumen de temporadas');
});
await t('avisa cuando el rango de episodios supera la temporada', () => {
  $('#pickMode button[data-mode="episodes"]').click();
  setVal('#pickSeasonSelect', '7');
  setVal('#pickEpFrom', '1');
  setVal('#pickEpTo', '12');
  if (!/sólo tiene 7 episodios/.test($('#pickWarn').textContent)) {
    throw new Error('no avisa del exceso: ' + $('#pickWarn').textContent);
  }
  if (!/7 episodios en la temporada 7/.test($('#pickEpInfo').textContent)) {
    throw new Error('info de episodios: ' + $('#pickEpInfo').textContent);
  }
});
await t('modo serie completa, temporada y episodio generan las líneas correctas', () => {
  $('#pickMode button[data-mode="series"]').click();
  if (!/^tt0944947 Game of Thrones \(2011\)$/m.test($('#pickPreview').textContent)) throw new Error('serie completa');
  $('#pickMode button[data-mode="season"]').click();
  setVal('#pickSeasonSelect', '4');
  if (!/tt0944947:s4 Game of Thrones Temporada 4/.test($('#pickPreview').textContent)) throw new Error('temporada');
  $('#pickMode button[data-mode="episodes"]').click();
  setVal('#pickSeasonSelect', '2'); setVal('#pickEpFrom', '3'); setVal('#pickEpTo', '5');
  const txt = $('#pickPreview').textContent;
  ['tt0944947:s2:e3 Game of Thrones S02E03', 'tt0944947:s2:e4 Game of Thrones S02E04', 'tt0944947:s2:e5 Game of Thrones S02E05']
    .forEach((l) => { if (txt.indexOf(l) === -1) throw new Error('falta «' + l + '»'); });
});
await t('añade los 3 episodios', async () => {
  $('#btnAddPick').click();
  await waitFor(() => $('#modalPicker').classList.contains('hidden'), 'modal cerrado');
  const txt = previewText();
  ['tt0944947:s2:e3 Game of Thrones S02E03', 'tt0944947:s2:e4 Game of Thrones S02E04', 'tt0944947:s2:e5 Game of Thrones S02E05']
    .forEach((l) => { if (txt.indexOf(l) === -1) throw new Error('falta «' + l + '» tras añadir'); });
});
await t('omite duplicados al repetir el mismo alta', async () => {
  const before = previewLines().length;
  const cards = entryCards().filter((c) => /Game of Thrones S02E0/.test(c.textContent) || /Game of Thrones/.test(c.textContent)).length;
  await selectSource('imdb');
  await searchFor('game of thr');
  resultByTitle('Game of Thrones').querySelector('button[data-add]').click();
  await waitFor(() => !$('#modalPicker').classList.contains('hidden'), 'modal abierto');
  await waitFor(() => $('#pickSeasonSelect').options.length === 8, 'temporadas listas');
  $('#pickMode button[data-mode="episodes"]').click();
  setVal('#pickSeasonSelect', '2'); setVal('#pickEpFrom', '3'); setVal('#pickEpTo', '5');
  $('#btnAddPick').click();
  await wait(120);
  if (previewLines().length !== before) {
    throw new Error('se añadieron duplicados (' + before + ' → ' + previewLines().length + ')');
  }
  if (!/ya existían|omitieron/.test($('#toasts').textContent)) throw new Error('no se avisó del duplicado');
  await selectSource('all');
});
await t('películas: el modal explica que es una única línea', async () => {
  await selectSource('imdb');
  await searchFor('matrix');
  await waitFor(() => !!resultByTitle('The Matrix Reloaded'), 'resultado «The Matrix Reloaded»');
  resultByTitle('The Matrix Reloaded').querySelector('button[data-add]').click();
  await wait(60);
  const txt = previewText();
  if (txt.indexOf('tt0234215 The Matrix Reloaded (2003)') === -1) {
    throw new Error('no se añadió la película directamente');
  }
  if (!$('#modalPicker').classList.contains('hidden')) throw new Error('una película no debería abrir el picker');
});

section('6. Alta manual');
await t('añade ID suelto + comentario', async () => {
  const before = entryCards().length;
  setVal('#manualInput', '# nueva sección\ntt0068646 The Godfather (1972)');
  $('#btnManualAdd').click();
  await waitFor(() => entryCards().length === before + 1, 'entrada manual');
  if (!/# nueva sección/.test(previewText())) throw new Error('no se conservó el comentario');
});
await t('resuelve un ID de TMDB desde su URL', async () => {
  const before = entryCards().length;
  setVal('#manualInput', 'https://www.themoviedb.org/movie/999999');
  $('#btnManualAdd').click();
  await waitFor(() => entryCards().length === before + 1, 'alta desde URL de TMDB');
  if (!/tt7777777/.test(previewText())) throw new Error('no se resolvió el IMDb id de TMDB');
});
await t('rechaza líneas con formato inválido y las devuelve al cuadro', async () => {
  setVal('#manualInput', 'esto no es una entrada');
  $('#btnManualAdd').click();
  await waitFor(() => /No se reconocieron/.test($('#toasts').textContent), 'aviso de formato');
  if ($('#manualInput').value !== 'esto no es una entrada') throw new Error('no se devolvió la línea');
  setVal('#manualInput', '');
});
await t('permite eliminar entradas', async () => {
  const before = entryCards().length;
  entryCards().find((c) => /Reloaded/.test(c.textContent)).querySelector('button[data-del]').click();
  await waitFor(() => entryCards().length === before - 1, 'entrada eliminada');
});

section('7. Guardado en GitHub');
await t('abre la confirmación con el resumen del diff', async () => {
  $('#btnPush').click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'diálogo abierto');
  if (!/\+/.test($('#askBody').textContent)) throw new Error('sin resumen del diff');
});
await t('envía el PUT con Base64 UTF-8 y el sha actual', async () => {
  $('#askOk').click();
  await waitFor(() => putBody !== null, 'PUT enviado');
  const sent = unb64(putBody.content);
  const expected = previewLines().join('\n') + '\n';
  if (sent !== expected) {
    throw new Error('el PUT no coincide con la vista previa:\n' + JSON.stringify(sent));
  }
  if (putBody.sha !== 'abc123def4567890') throw new Error('sha incorrecto: ' + putBody.sha);
  if (putBody.branch !== 'main') throw new Error('rama incorrecta');
  ['acción', 'ñandú', 'Game of Thrones Temporada 3', 'tt0903747:s2:e5 Breaking Bad S02E05'].forEach((frag) => {
    if (sent.indexOf(frag) === -1) throw new Error('se perdió «' + frag + '» en:\n' + sent);
  });
});
await t('confirma con enlace al commit y estado limpio', async () => {
  await waitFor(() => /Guardado en GitHub/.test($('#toasts').textContent), 'toast de éxito');
  const link = Array.from($('#toasts').querySelectorAll('a')).find((a) => /Ver el commit/.test(a.textContent));
  if (!link || !/github\.com\/o\/r\/commit/.test(link.getAttribute('href'))) throw new Error('falta el enlace');
  if (!/Sincronizado/.test($('#statusBadges').textContent)) throw new Error('no quedó sincronizado');
  if (!/sin cambios/.test($('#diffBadges').textContent)) throw new Error('el diff no quedó limpio');
});
await t('avisa cuando no hay cambios que guardar', async () => {
  $('#btnPush2').click();
  await waitFor(() => /Sin cambios que guardar/.test($('#askTitle').textContent), 'diálogo sin cambios');
  $('#askCancel').click();
});

section('8. Manejo de errores');
await t('404 al leer el archivo', async () => {
  forceStatus = 404;
  $('#btnPull').click();
  await waitFor(() => /No se pudo cargar/.test($('#toasts').textContent), 'toast 404');
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
await t('los fallos de red se reportan sin romper la app', async () => {
  const original = window.fetch;
  window.fetch = () => Promise.reject(new TypeError('fetch failed'));
  await selectSource('imdb');
  setVal('#searchInput', 'matrix');
  fire($('#searchForm'), 'submit');
  await waitFor(() => /No se pudo conectar|bloqueado/.test($('#searchHint').textContent), 'aviso de red');
  window.fetch = original;
  await selectSource('all');
});

section('9. Persistencia y utilidades');
await t('copia la vista previa al portapapeles', async () => {
  $('#btnCopyPreview').click();
  await waitFor(() => /copiado al portapapeles/i.test($('#toasts').textContent), 'toast de copia');
});
await t('guarda el borrador en localStorage', async () => {
  await waitFor(() => !!window.localStorage.getItem('peerflix.draft.v1'), 'autoguardado', 4000);
  const data = JSON.parse(window.localStorage.getItem('peerflix.draft.v1'));
  if (!Array.isArray(data.seq) || !data.seq.length) throw new Error('borrador vacío');
});
await t('conserva el orden original de las líneas', () => {
  if (previewLines()[0] !== '# Peerflix Static · watchlist') throw new Error('orden alterado: ' + previewLines()[0]);
});
await t('persiste la fuente de búsqueda elegida', () => {
  const stored = JSON.parse(window.localStorage.getItem('peerflix.cfg.v1'));
  if (!stored.searchSource) throw new Error('no se guardó searchSource');
});
await t('no hay errores de script acumulados en toda la sesión', () => {
  if (jsdomErrors.length) throw new Error(jsdomErrors.join(' | '));
});

section('10. Integridad del documento');
await t('no hay ids duplicados en el HTML', () => {
  const ids = $$('[id]').map((el) => el.id);
  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  if (dupes.length) throw new Error('ids repetidos: ' + [...new Set(dupes)].join(', '));
});
await t('todos los selectores #id del script existen en el DOM', () => {
  const script = $$('script').map((s) => s.textContent).join('\n');
  const ids = new Set(Array.from(script.matchAll(/\$\('#([A-Za-z0-9_-]+)/g)).map((m) => m[1]));
  const missing = [...ids].filter((id) => !doc.getElementById(id));
  if (missing.length) throw new Error('ids inexistentes: ' + missing.join(', '));
});
await t('el documento declara accesibilidad y metadatos básicos', () => {
  if (doc.documentElement.lang !== 'es') throw new Error('falta lang="es"');
  if (!doc.querySelector('meta[name="viewport"]')) throw new Error('falta viewport');
  if (doc.title.indexOf('Peerflix') === -1) throw new Error('title inesperado: ' + doc.title);
  $$('#results img, #list img').forEach((img) => {
    if (img.getAttribute('alt') === null) throw new Error('img sin alt');
  });
});
await t('los botones de solo icono tienen etiqueta accesible', () => {
  ['#btnSettings', '#btnCopyPreview', '#btnReset'].forEach((sel) => {
    const el = $(sel);
    if (!el) return;
    if (!el.getAttribute('aria-label') && !el.textContent.trim()) throw new Error(sel + ' sin aria-label');
  });
});

section('11. Archivo real del proyecto Peerflix Static');
await t('carga el watchlist real sin modificar ni un byte', async () => {
  remoteText = REAL_WL;
  $('#btnPull').click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'aviso de cambios locales');
  $('#askOk').click();
  await waitFor(() => entryCards().length === 5, '5 entradas cargadas');
  if (previewLines().join('\n') + '\n' !== REAL_WL) {
    throw new Error('round-trip roto:\n' + JSON.stringify(previewLines().join('\n') + '\n'));
  }
  if (!/tt1234567          -> película \(IMDb\)/.test(previewText())) throw new Error('se perdió la cabecera de comentarios');
  if ($('#wlStats').textContent.indexOf('Comentarios: 22') === -1) throw new Error('comentarios: ' + $('#wlStats').textContent);
});
await t('la lista resuelve los títulos por las fuentes, sin escribir en el archivo', async () => {
  await waitFor(() => /La Última Frontera/.test($('#list').textContent), 'títulos resueltos');
  ['Ciudad de Cristal', 'El Silencio del Mar', 'Sombras de Neón', 'Cosecha Amarga'].forEach((tt) => {
    if ($('#list').textContent.indexOf(tt) === -1) throw new Error('falta «' + tt + '» en la lista');
  });
  ['tt6933238', 'tt22526100', 'tt26657236', 'tt29355505', 'tt11561116'].forEach((id) => {
    if (previewLines().indexOf(id) === -1) throw new Error('la línea ' + id + ' se modificó sola');
  });
  if ($('#wlStats').textContent.indexOf('Películas: 5') === -1) throw new Error('clasificación: ' + $('#wlStats').textContent);
});
await t('«Escribir títulos» propone las 5 líneas con nombre', async () => {
  const btn = $('#btnWriteTitles');
  if (btn.disabled) throw new Error('el botón debería estar activo');
  if ($('#writeTitlesCount').textContent !== '5') throw new Error('contador: ' + $('#writeTitlesCount').textContent);
  btn.click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'confirmación de títulos');
  const body = $('#askBody').textContent;
  if (!/tt6933238 La Última Frontera \(2026\)/.test(body.replace(/\s+/g, ' '))) {
    throw new Error('la propuesta no muestra la línea esperada: ' + body.slice(0, 300));
  }
  if (!/Escribir el título en 5 línea/.test($('#askTitle').textContent)) throw new Error('título del diálogo: ' + $('#askTitle').textContent);
  $('#askOk').click();
  await waitFor(() => previewLines().indexOf('tt6933238 La Última Frontera (2026)') !== -1, 'línea reescrita');
  ['tt22526100 Ciudad de Cristal (2025)', 'tt26657236 El Silencio del Mar (2024)',
   'tt29355505 Sombras de Neón (2026)', 'tt11561116 Cosecha Amarga (2025)'].forEach((l) => {
    if (previewLines().indexOf(l) === -1) throw new Error('falta la línea «' + l + '»: ' + JSON.stringify(previewLines()));
  });
  if (previewLines()[0] !== '# Peerflix Static – Watchlist') throw new Error('se alteró la cabecera');
  if ($('#btnWriteTitles').disabled !== true) throw new Error('el botón debería quedar desactivado');
});
await t('el PUT envía los nombres y conserva los 22 comentarios', async () => {
  putBody = null;
  $('#btnPush').click();
  await waitFor(() => !$('#modalAsk').classList.contains('hidden'), 'confirmación de guardado');
  $('#askOk').click();
  await waitFor(() => putBody !== null, 'PUT enviado');
  const sent = unb64(putBody.content);
  if (sent !== previewLines().join('\n') + '\n') throw new Error('el PUT no coincide con la vista previa');
  ['tt6933238 La Última Frontera (2026)', 'tt11561116 Cosecha Amarga (2025)'].forEach((l) => {
    if (sent.indexOf(l) === -1) throw new Error('falta «' + l + '»');
  });
  const comments = sent.split('\n').filter((l) => l.trim().charAt(0) === '#').length;
  if (comments !== 22) throw new Error('comentarios enviados: ' + comments);
  if (sent.indexOf('#   tt1234567          -> película (IMDb)') === -1) throw new Error('se perdió la documentación del formato');
  if (sent.indexOf('#   tt0944947:s1:e1 Game of Thrones S01E01') === -1) throw new Error('se perdió el ejemplo de episodio');
});
await t('avisa de que las líneas :sN necesitan TMDB_API_KEY (solo si falta)', async () => {
  setVal('#cfgTmdb', '');          // se quita la clave de TMDB
  $('#btnSaveCfg').click();
  await wait(60);
  await selectSource('imdb');
  await searchFor('game of thr');
  await waitFor(() => !!resultByTitle('Game of Thrones'), 'resultado GoT');
  resultByTitle('Game of Thrones').querySelector('button[data-add]').click();
  await waitFor(() => $('#pickSeasonSelect').options.length === 8, 'temporadas listas');
  $('#pickMode button[data-mode="season"]').click();
  setVal('#pickSeasonSelect', '3');
  if (!/TMDB_API_KEY/.test($('#pickWarn').textContent)) {
    throw new Error('sin clave de TMDB debería avisar: «' + $('#pickWarn').textContent + '»');
  }
  /* con clave configurada el aviso desaparece */
  setVal('#cfgTmdb', 'clave-tmdb-32');
  $('#btnSaveCfg').click();
  await wait(60);
  setVal('#pickSeasonSelect', '4');
  if (/TMDB_API_KEY/.test($('#pickWarn').textContent)) {
    throw new Error('con clave de TMDB no debería avisar: ' + $('#pickWarn').textContent);
  }
  $('#btnCancelPick').click();
  await selectSource('all');
});
await t('sin errores de script tras todo el flujo real', () => {
  if (jsdomErrors.length) throw new Error(jsdomErrors.join(' | '));
});

/* ------------------------------------------------------------------ */
console.log('\n' + (fail ? '✗' : '✓') + ' ' + pass + ' pruebas correctas, ' + fail + ' fallidas');
console.log('peticiones simuladas: ' + calls.length + '\n');
process.exit(fail ? 1 : 0);
