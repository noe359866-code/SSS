/**
 * Pruebas del núcleo de Peerflix Static · Watchlist Manager.
 * Extrae el <script> de la app directamente desde index.html y lo evalúa en Node.
 *   node tests/core.test.mjs
 */
import { readFileSync } from 'node:fs';
import { strict as assert } from 'node:assert';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(root, 'index.html'), 'utf8');

const blocks = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
const app = blocks.sort((a, b) => b.length - a.length)[0];
assert.ok(app && app.length > 10000, 'No se pudo extraer el script de la aplicación');

const sandbox = { window: {}, localStorage: undefined, sessionStorage: undefined, console };
const fn = new Function('window', 'console', 'localStorage', 'sessionStorage', 'document', app + '\n;return window.PFX_CORE;');
const CORE = fn(sandbox.window, console, undefined, undefined, undefined);
assert.ok(CORE, 'PFX_CORE no está expuesto');
const {
  parseWatchlist, serializeWatchlist, lineFor, lineWithTitle, idPartOf, makeEntry, splitTitleYear,
  parseManualLine, diffLines, statsOf, b64EncodeUtf8, b64DecodeUtf8, imdbQidToType,
  normTitle, relevanceOf, mergeResults, rankResults
} = CORE;

let pass = 0, fail = 0;
const t = (name, f) => { try { f(); console.log('  ✓ ' + name); pass++; } catch (e) { console.log('  ✗ ' + name + '\n      ' + e.message); fail++; } };
const section = (s) => console.log('\n' + s);

/* ------------------------------------------------------------------ */
section('1. Parser del formato watchlist.txt (especificación)');

const FIXTURE = [
  '# Peerflix Static · watchlist de ejemplo',
  '# comentario con acentos: acción, ñandú',
  '',
  'tt0111161 The Shawshank Redemption (1994)',
  'tt0944947:s3 Game of Thrones Temporada 3',
  'tt0903747:s2:e5 Breaking Bad S02E05',
  'tt0133093',
  'esta línea no cumple el formato'
].join('\n') + '\n';

const seq = parseWatchlist(FIXTURE);

t('reconoce comentarios', () => {
  assert.equal(seq[0].kind, 'comment');
  assert.match(seq[0].text, /^# Peerflix Static/);
});
t('preserva la línea en blanco', () => assert.equal(seq[2].kind, 'blank'));
t('película IMDb: id + título + año', () => {
  const e = seq[3].entry;
  assert.equal(seq[3].kind, 'entry');
  assert.equal(e.imdbId, 'tt0111161');
  assert.equal(e.title, 'The Shawshank Redemption');
  assert.equal(e.year, '1994');
  assert.equal(e.season, null);
  assert.equal(lineFor(e), 'tt0111161 The Shawshank Redemption (1994)');
});
t('temporada completa :s3', () => {
  const e = seq[4].entry;
  assert.equal(e.season, 3);
  assert.equal(e.episode, null);
  assert.equal(e.mediaType, 'series');
  assert.equal(e.title, 'Game of Thrones');
  assert.equal(lineFor(e), 'tt0944947:s3 Game of Thrones Temporada 3');
});
t('episodio :s2:e5', () => {
  const e = seq[5].entry;
  assert.equal(e.season, 2);
  assert.equal(e.episode, 5);
  assert.equal(lineFor(e), 'tt0903747:s2:e5 Breaking Bad S02E05');
});
t('ID simple sin texto', () => {
  const e = seq[6].entry;
  assert.equal(e.imdbId, 'tt0133093');
  assert.equal(e.label, '');
  assert.equal(lineFor(e), 'tt0133093');
});
t('línea inválida marcada, no descartada', () => {
  assert.equal(seq[7].kind, 'invalid');
  assert.equal(seq[7].text, 'esta línea no cumple el formato');
});

/* ------------------------------------------------------------------ */
section('2. Round-trip exacto (no se pierde ni se reordena nada)');

t('serialize(parse(x)) === x', () => assert.equal(serializeWatchlist(seq, { finalNewline: true }), FIXTURE));
t('sin salto final cuando finalNewline=false', () => {
  assert.equal(serializeWatchlist(seq, { finalNewline: false }), FIXTURE.replace(/\n$/, ''));
});
t('acepta CRLF de GitHub', () => {
  const crlf = FIXTURE.replace(/\n/g, '\r\n');
  assert.equal(serializeWatchlist(parseWatchlist(crlf), { finalNewline: true }), FIXTURE);
});
t('preserva texto original (no reescribe acentos)', () => {
  const s = parseWatchlist('tt1234567 Amélie (2001)\ntt7654321:s1 Ñandú Salvaje Temporada 1\n');
  assert.equal(serializeWatchlist(s, { finalNewline: true }), 'tt1234567 Amélie (2001)\ntt7654321:s1 Ñandú Salvaje Temporada 1\n');
});

/* ------------------------------------------------------------------ */
section('3. Creación de entradas (picker temporada/episodio y manual)');

t('serie completa', () => {
  const e = makeEntry({ imdbId: 'tt0944947', title: 'Game of Thrones', year: '2011', mediaType: 'series' });
  assert.equal(lineFor(e), 'tt0944947 Game of Thrones (2011)');
});
t('temporada con formato del archivo', () => {
  const e = makeEntry({ imdbId: 'tt0944947', title: 'Game of Thrones', season: 3, mediaType: 'series' });
  assert.equal(lineFor(e), 'tt0944947:s3 Game of Thrones Temporada 3');
});
t('episodio con formato SxxEyy', () => {
  const e = makeEntry({ imdbId: 'tt0903747', title: 'Breaking Bad', season: 2, episode: 5, mediaType: 'series' });
  assert.equal(lineFor(e), 'tt0903747:s2:e5 Breaking Bad S02E05');
});
t('episodio 10+ conserva dos dígitos', () => {
  const e = makeEntry({ imdbId: 'tt0903747', title: 'Breaking Bad', season: 5, episode: 14, mediaType: 'series' });
  assert.equal(lineFor(e), 'tt0903747:s5:e14 Breaking Bad S05E14');
});
t('episodio sin temporada cae en la 1', () => {
  const e = makeEntry({ imdbId: 'tt0903747', title: 'Show', episode: 3 });
  assert.equal(e.season, 1);
  assert.equal(lineFor(e), 'tt0903747:s1:e3 Show S01E03');
});
t('sin título → solo el ID', () => {
  assert.equal(lineFor(makeEntry({ imdbId: 'tt0133093' })), 'tt0133093');
});
t('splitTitleYear', () => {
  assert.deepEqual(splitTitleYear('Dune: Parte Dos (2024)'), { title: 'Dune: Parte Dos', year: '2024' });
  assert.deepEqual(splitTitleYear('Breaking Bad'), { title: 'Breaking Bad', year: '' });
});

/* ------------------------------------------------------------------ */
section('4. Entrada manual (IDs, URLs y líneas completas)');

t('ID suelto', () => {
  const r = parseManualLine('  tt0111161 ');
  assert.equal(r.type, 'entry');
  assert.equal(r.entry.imdbId, 'tt0111161');
});
t('URL de IMDb', () => {
  const r = parseManualLine('https://www.imdb.com/title/tt0944947/?ref_=fn_al_tt_1');
  assert.equal(r.entry.imdbId, 'tt0944947');
});
t('URL de TMDB (película)', () => {
  const r = parseManualLine('https://www.themoviedb.org/movie/603692');
  assert.equal(r.type, 'entry');
  assert.equal(r.entry.tmdbId, '603692');
  assert.equal(r.entry.mediaType, 'movie');
});
t('línea completa con episodio', () => {
  const r = parseManualLine('tt0903747:s2:e5 Breaking Bad S02E05');
  assert.equal(r.entry.season, 2);
  assert.equal(r.entry.episode, 5);
  assert.equal(lineFor(r.entry), 'tt0903747:s2:e5 Breaking Bad S02E05');
});
t('comentario', () => assert.equal(parseManualLine('# sección series').type, 'comment'));
t('basura → invalid', () => assert.equal(parseManualLine('hola qué tal').type, 'invalid'));

/* ------------------------------------------------------------------ */
section('5. Base64 UTF-8 (acentos, ñ, emoji) → GitHub Contents API');

const SAMPLES = ['Amélie (2001)', 'Ñandú salvaje', 'Cien años de soledad', '完美世界 中文', 'ÁÉÍÓÚ áéíóú ñÑ ¿¡', '🎬 Película'];
SAMPLES.forEach((s, i) => {
  t('b64EncodeUtf8/b64DecodeUtf8 (' + (i + 1) + '): ' + s, () => assert.equal(b64DecodeUtf8(b64EncodeUtf8(s)), s));
});
t('coincide con Buffer base64 (misma semántica UTF-8)', () => {
  SAMPLES.forEach((s) => assert.equal(b64EncodeUtf8(s), Buffer.from(s, 'utf8').toString('base64')));
});
t('archivo completo con acentos sobrevive al ciclo', () => {
  const text = 'tt1234567 Amélie (2001)\ntt7654321:s1 Ñandú Salvaje Temporada 1\n';
  assert.equal(b64DecodeUtf8(b64EncodeUtf8(text)), text);
});

/* ------------------------------------------------------------------ */
section('6. Diff, estadísticas y tipos');

t('diff detecta altas', () => {
  const d = diffLines('tt1 A\n', 'tt1 A\ntt2 B\n');
  assert.deepEqual(d.added, ['tt2 B']);
  assert.deepEqual(d.removed, []);
});
t('diff detecta bajas y duplicados', () => {
  const d = diffLines('tt1 A\ntt1 A\ntt3 C\n', 'tt1 A\n');
  assert.deepEqual(d.removed.sort(), ['tt1 A', 'tt3 C']);
});
t('diff ignora la línea vacía final', () => assert.deepEqual(diffLines('tt1 A\n', 'tt1 A'), { added: [], removed: [] }));
t('stats cuenta cada tipo', () => {
  const s = statsOf(parseWatchlist(FIXTURE));
  assert.equal(s.entries, 4);
  assert.equal(s.movies, 0);          // sin key de OMDb el tipo es desconocido
  assert.equal(s.unknown, 2);          // película + ID suelto: sin datos de tipo
  assert.equal(s.series, 0);
  assert.equal(s.seasons, 1);
  assert.equal(s.episodes, 1);
  assert.equal(s.comments, 2);
  assert.equal(s.invalid, 1);
  assert.equal(s.blanks, 1);
});
t('imdbQidToType mapea tipos de IMDb Suggest', () => {
  assert.equal(imdbQidToType('movie'), 'movie');
  assert.equal(imdbQidToType('tvSeries'), 'series');
  assert.equal(imdbQidToType('tvMiniSeries'), 'series');
  assert.equal(imdbQidToType('videoGame'), null);
});

/* ------------------------------------------------------------------ */
section('7. Fusión y ordenación de resultados de varias fuentes');

const res = (source, imdbId, title, year, extra) => Object.assign({
  key: '', source, sources: [], imdbId: imdbId || '', title, year: year || '', mediaType: 'movie',
  poster: '', subtitle: '', overview: '', tmdbId: null, tmdbKind: null, seasons: null
}, extra || {});

t('normTitle ignora acentos, mayúsculas y signos', () => {
  assert.equal(normTitle('Amélie'), 'amelie');
  assert.equal(normTitle('El Señor de los Anillos: La Comunidad'), 'el senor de los anillos la comunidad');
  assert.equal(normTitle(null), '');
});
t('relevanceOf prioriza coincidencias exactas', () => {
  assert.equal(relevanceOf('The Matrix', 'The Matrix'), 0);
  assert.equal(relevanceOf('The Matrix Reloaded', 'the matrix'), 1);
  assert.equal(relevanceOf('Game of Thrones', 'matrix'), 3);
});
t('fusiona la misma película de varias fuentes por ID de IMDb', () => {
  const out = mergeResults([
    res('imdb', 'tt0133093', 'The Matrix', '1999'),
    res('imdbapi', 'tt0133093', 'The Matrix', '1999'),
    res('cinemeta', 'tt0133093', 'The Matrix', '1999')
  ]);
  assert.equal(out.length, 1);
  assert.deepEqual(out[0].sources.sort(), ['cinemeta', 'imdb', 'imdbapi']);
});
t('absorbe el duplicado sin IMDb id (TMDB) en la ficha con IMDb', () => {
  const out = mergeResults([
    res('imdb', 'tt0133093', 'The Matrix', '1999'),
    res('tmdb', '', 'The Matrix', '1999', { tmdbId: '603', tmdbKind: 'movie', poster: 't/p.jpg' })
  ]);
  assert.equal(out.length, 1);
  assert.equal(out[0].imdbId, 'tt0133093');
  assert.equal(out[0].tmdbId, '603');
  assert.equal(out[0].poster, 't/p.jpg');
  assert.deepEqual(out[0].sources.sort(), ['imdb', 'tmdb']);
});
t('completa campos que falten con los de cualquier fuente', () => {
  const out = mergeResults([
    res('imdb', 'tt0944947', 'Game of Thrones', '2011'),
    res('tvmaze', 'tt0944947', 'Game of Thrones', '2011', { poster: 'p.jpg', overview: 'sinopsis' }),
    res('cinemeta', 'tt0944947', 'Game of Thrones', '2011', { seasons: { count: 8, source: 'cinemeta' } })
  ]);
  assert.equal(out.length, 1);
  assert.equal(out[0].poster, 'p.jpg');
  assert.equal(out[0].overview, 'sinopsis');
  assert.equal(out[0].seasons.count, 8);
});
t('no fusiona títulos distintos ni películas con el mismo nombre y año distinto', () => {
  const out = mergeResults([
    res('imdb', 'tt0133093', 'The Matrix', '1999'),
    res('imdb', 'tt0234215', 'The Matrix Reloaded', '2003'),
    res('imdb', 'tt10838180', 'The Matrix Resurrections', '2021')
  ]);
  assert.equal(out.length, 3);
});
t('mantiene separados los resultados sin ID aunque compartan título', () => {
  const out = mergeResults([
    res('anilist', '', 'Kaiju No. 8', '2024'),
    res('simkl', '', 'Kaiju No. 8', '2024')
  ]);
  assert.equal(out.length, 1);
  assert.deepEqual(out[0].sources.sort(), ['anilist', 'simkl']);
});
t('ordena por relevancia y, a igualdad, por número de fuentes', () => {
  const merged = mergeResults([
    res('imdb', 'tt0133093', 'The Matrix', '1999'),
    res('imdbapi', 'tt0133093', 'The Matrix', '1999'),
    res('imdb', 'tt0234215', 'The Matrix Reloaded', '2003'),
    res('omdb', 'tt9243946', 'El camino', '2019')
  ]);
  const ranked = rankResults(merged, 'matrix');
  assert.equal(ranked[0].title, 'The Matrix');
  assert.equal(ranked[1].title, 'The Matrix Reloaded');
  assert.equal(ranked[2].title, 'El camino');
});
t('la fusión no muta los objetos originales de entrada', () => {
  const a = res('imdb', 'tt0133093', 'The Matrix', '1999');
  const b = res('tmdb', '', 'The Matrix', '1999');
  mergeResults([a, b]);
  assert.deepEqual(a.sources, []);
  assert.deepEqual(b.sources, []);
});

/* ------------------------------------------------------------------ */
section('8. Archivo real de Peerflix Static (solo IDs + cabecera de comentarios)');

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

const realSeq = parseWatchlist(REAL_WL);

t('round-trip byte a byte del archivo real', () => {
  assert.equal(serializeWatchlist(realSeq, { finalNewline: true }), REAL_WL);
});
t('clasifica comentarios, línea en blanco y 5 entradas', () => {
  const st = statsOf(realSeq);
  assert.equal(st.comments, 22);
  assert.equal(st.blanks, 1);
  assert.equal(st.entries, 5);
  assert.equal(st.unknown, 5);
  assert.equal(st.lines, 28);
});
t('acepta IDs de 7 y 8 dígitos (tt6933238, tt22526100…)', () => {
  const ids = realSeq.filter((it) => it.kind === 'entry').map((it) => it.entry.imdbId);
  assert.deepEqual(ids, ['tt6933238', 'tt22526100', 'tt26657236', 'tt29355505', 'tt11561116']);
});
t('las líneas sin texto quedan marcadas como «solo ID» (no se reescriben solas)', () => {
  const e = realSeq[23].entry;
  assert.equal(e.pinned, true);
  assert.equal(e.label, '');
  assert.equal(lineFor(e), 'tt6933238');
  /* aunque la interfaz complete metadatos, la línea del archivo no cambia */
  e.title = 'La Última Frontera';
  e.year = '2026';
  e.mediaType = 'movie';
  assert.equal(lineFor(e), 'tt6933238');
});
t('«escribir título» genera la línea con nombre y año', () => {
  const e = realSeq[23].entry;
  assert.equal(lineWithTitle(e), 'tt6933238 La Última Frontera (2026)');
});
t('«escribir título» respeta el formato de temporada y episodio', () => {
  const season = makeEntry({ imdbId: 'tt0944947', title: 'Game of Thrones', season: 3, episode: null, mediaType: 'series' });
  const episode = makeEntry({ imdbId: 'tt0944947', title: 'Game of Thrones', season: 3, episode: 4, mediaType: 'series' });
  const plain = makeEntry({ imdbId: 'tt0944947', title: 'Game of Thrones', year: '2011', mediaType: 'series' });
  assert.equal(lineWithTitle(season), 'tt0944947:s3 Game of Thrones Temporada 3');
  assert.equal(lineWithTitle(episode), 'tt0944947:s3:e4 Game of Thrones S03E04');
  assert.equal(lineWithTitle(plain), 'tt0944947 Game of Thrones (2011)');
  assert.equal(idPartOf(episode), 'tt0944947:s3:e4');
});
t('sin metadatos, «escribir título» deja la línea como estaba', () => {
  const e = makeEntry({ imdbId: 'tt9999999', season: 3 });
  assert.equal(lineWithTitle(e), 'tt9999999:s3');
});
t('la cabecera sobrevive a añadir entradas nuevas', () => {
  const copy = realSeq.slice();
  copy.push({ kind: 'entry', entry: makeEntry({ imdbId: 'tt0111161', title: 'Cadena perpetua', year: '1994' }) });
  const out = serializeWatchlist(copy, { finalNewline: true });
  assert.ok(out.indexOf('# -----------------------------------------------------------') !== -1);
  assert.ok(out.indexOf('#   tt1234567          -> pel\u00edcula (IMDb)') !== -1);
  assert.ok(out.endsWith('tt0111161 Cadena perpetua (1994)\n'));
});
t('los comentarios con formato de entrada no se confunden con entradas', () => {
  assert.equal(realSeq[7].kind, 'comment');   // «#   tt1234567          -> película (IMDb)»
  assert.equal(realSeq[15].kind, 'comment');  // «#   tt0111161 Cadena perpetua (1994)»
});

/* ------------------------------------------------------------------ */
console.log('\n' + (fail ? '✗' : '✓') + ' ' + pass + ' pruebas correctas, ' + fail + ' fallidas\n');
process.exit(fail ? 1 : 0);
