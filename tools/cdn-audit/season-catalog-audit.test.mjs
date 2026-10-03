import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSeasons, validateEpisodes, loadCatalog, validateIdentity } from './season-catalog-audit.mjs';

test('Prime ASINs retain letters and case; provider IDs are never converted to numbers', () => {
  const identity = { ott: 'pv', showId: '0L52QDYY6OG738LB7ILP0VB7R4' };
  assert.equal(validateIdentity(identity).showId, identity.showId);
  assert.deepEqual(parseSeasons({ seasons: [{ id: identity.showId, s: 'S1' }] }),
    [{ number: 1, id: identity.showId }]);
  assert.equal(validateEpisodes({ episodes: [{ id: '0RZED4V5SOLKXX2U04B6XONCIM', s: 'S1', ep: 'E1' }] }, { number: 1 })[0].id,
    '0RZED4V5SOLKXX2U04B6XONCIM');
  assert.throws(() => validateIdentity({ ott: 'pv', showId: 123 }), /invalid_identity/);
});

test('rejects Invalid User even with HTTP 200; never infers IDs from numeric proximity', () => {
  assert.throws(() => parseSeasons({ status: 'n', error: 'Invalid User' }), /catalog_rejected/);
  assert.deepEqual(parseSeasons({ season: [{ id: '70037632', s: 'S4' }, { id: '123', s: 'Season 3' }] }),
    [{ number: 3, id: '123' }, { number: 4, id: '70037632' }]);
  assert.throws(() => parseSeasons({ seasons: [{ id: 'a' }] }), /identity_missing/);
  assert.throws(() => parseSeasons({ seasons: [{ id: 'a', s: 'S4' }, { id: 'b', s: 'S4' }] }), /duplicate/);
});
test('episode season mismatch fails rather than accepting any season-labelled response', () => {
  assert.throws(() => validateEpisodes({ episodes: [{ id: 'wrong', s: 'S3', ep: '1' }] }, { number: 4 }), /season_mismatch/);
  assert.throws(() => validateEpisodes({ error: 'Invalid User' }, { number: 4 }), /episodes_rejected/);
});
test('cache is scoped to provider, catalog, show and time; offline reuse needs no session', () => {
  const identity = { baseUrl: 'https://provider.invalid', showId: '123', ott: 'nf' };
  const cache = { schema: 1, ...identity, fetchedAt: 1000, seasons: [{ id: '456', number: 4, episodes: [] }] };
  assert.equal(loadCatalog(cache, identity, 2000), cache);
  assert.throws(() => loadCatalog(cache, { ...identity, showId: '124' }, 2000), /identity_mismatch/);
  assert.throws(() => loadCatalog(cache, { ...identity, ott: 'pv' }, 2000), /identity_mismatch/);
  assert.throws(() => loadCatalog(cache, identity, 86401001), /expired/);
});
