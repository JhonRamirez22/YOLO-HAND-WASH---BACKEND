import assert from 'node:assert/strict';
import test from 'node:test';
import {
  defaultPrivilegeStatements,
  quoteIdentifier,
  quoteLiteral,
  readRdsCaBundle,
} from '../lambda/database-role-bootstrap.js';

test('SQL quoting treats secret values as literals and identifiers as names', () => {
  assert.equal(quoteIdentifier('handwash"migrator'), '"handwash""migrator"');
  assert.equal(quoteLiteral("secret' OR true --"), "'secret'' OR true --'");
});

test('packaged Amazon RDS CA bundle contains valid X.509 roots', () => {
  const bundle = readRdsCaBundle();
  assert.ok(bundle.includes('-----BEGIN CERTIFICATE-----'));
  assert.ok(bundle.length < 512 * 1024);
});

test('migrator applies defaults to objects it creates without widening owner membership', () => {
  const statements = defaultPrivilegeStatements();
  assert.deepEqual(statements, [
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO handwash_runtime',
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO handwash_runtime',
  ]);
  assert.ok(statements.every((statement) => !statement.includes('FOR ROLE')));
});
