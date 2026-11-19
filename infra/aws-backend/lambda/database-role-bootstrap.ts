import {
  GetSecretValueCommand,
  SecretsManagerClient,
} from '@aws-sdk/client-secrets-manager';
import { X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { CloudFormationCustomResourceEvent } from 'aws-lambda';
import { Client, type ClientConfig } from 'pg';

interface DatabaseCredentials {
  readonly username: string;
  readonly password: string;
}

const secrets = new SecretsManagerClient({});
const physicalResourceId = 'handwash-postgres-roles-v1';
const rdsCaBundle = readRdsCaBundle();

export async function handler(event: CloudFormationCustomResourceEvent) {
  if (event.RequestType === 'Delete') return { PhysicalResourceId: physicalResourceId };

  const [owner, runtime, migrator] = await Promise.all([
    readSecret(requiredEnv('DB_OWNER_SECRET_ARN')),
    readSecret(requiredEnv('DB_RUNTIME_SECRET_ARN')),
    readSecret(requiredEnv('DB_MIGRATION_SECRET_ARN')),
  ]);
  validateCredentials(runtime, 'handwash_runtime');
  validateCredentials(migrator, 'handwash_migrator');

  const host = requiredEnv('DB_HOST');
  const clientConfig: ClientConfig = {
    host,
    port: Number(requiredEnv('DB_PORT')),
    database: requiredEnv('DB_NAME'),
    user: owner.username,
    password: owner.password,
    ssl: { rejectUnauthorized: true, servername: host, ca: rdsCaBundle },
    connectionTimeoutMillis: 10_000,
  };
  const ownerClient = await connectWithRetry(clientConfig);
  try {
    await inTransaction(ownerClient, async () => {
      await upsertLoginRole(ownerClient, runtime);
      await upsertLoginRole(ownerClient, migrator);
      await ownerClient.query('REVOKE CREATE ON SCHEMA public FROM PUBLIC');
      await ownerClient.query('GRANT CONNECT ON DATABASE handwash TO handwash_runtime, handwash_migrator');
      await ownerClient.query('ALTER SCHEMA public OWNER TO handwash_migrator');
      await ownerClient.query('GRANT USAGE ON SCHEMA public TO handwash_runtime');
      await ownerClient.query('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO handwash_runtime');
      await ownerClient.query('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO handwash_runtime');
    });
  } finally {
    await ownerClient.end();
  }

  const migratorClient = await connectWithRetry({
    ...clientConfig,
    user: migrator.username,
    password: migrator.password,
  });
  try {
    await inTransaction(migratorClient, async () => {
      for (const statement of defaultPrivilegeStatements()) await migratorClient.query(statement);
    });
  } finally {
    await migratorClient.end();
  }

  return { PhysicalResourceId: physicalResourceId };
}

async function readSecret(secretArn: string): Promise<DatabaseCredentials> {
  const response = await secrets.send(new GetSecretValueCommand({ SecretId: secretArn }));
  if (!response.SecretString) throw new Error('Database secret is empty.');
  const parsed: unknown = JSON.parse(response.SecretString);
  if (!isCredentials(parsed)) throw new Error('Database secret has an invalid shape.');
  return parsed;
}

function isCredentials(value: unknown): value is DatabaseCredentials {
  if (typeof value !== 'object' || value === null) return false;
  const data = value as Record<string, unknown>;
  return typeof data.username === 'string' && typeof data.password === 'string';
}

function validateCredentials(credentials: DatabaseCredentials, expectedUsername: string): void {
  if (credentials.username !== expectedUsername || credentials.password.length < 32) {
    throw new Error(`Database secret does not match the required ${expectedUsername} role.`);
  }
}

async function upsertLoginRole(client: Client, credentials: DatabaseCredentials): Promise<void> {
  const roles = await client.query<{ rolname: string }>(
    'SELECT rolname FROM pg_roles WHERE rolname = $1',
    [credentials.username],
  );
  const roleName = quoteIdentifier(credentials.username);
  const password = quoteLiteral(credentials.password);
  if (roles.rowCount === 0) {
    await client.query(`CREATE ROLE ${roleName} LOGIN PASSWORD ${password}`);
  } else {
    await client.query(`ALTER ROLE ${roleName} LOGIN PASSWORD ${password}`);
  }
}

export function quoteIdentifier(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}

export function quoteLiteral(value: string): string {
  return `'${value.replaceAll("'", "''")}'`;
}

export function defaultPrivilegeStatements(): string[] {
  return [
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO handwash_runtime',
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO handwash_runtime',
  ];
}

export function readRdsCaBundle(): string {
  const bundle = readFileSync(join(__dirname, 'certs', 'global-bundle.pem'), 'utf8');
  const certificates = bundle.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g);
  const remainder = bundle.replace(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g, '').trim();

  if (Buffer.byteLength(bundle, 'utf8') > 512 * 1024 || !certificates?.length || remainder) {
    throw new Error('The packaged Amazon RDS CA bundle is invalid.');
  }

  try {
    for (const certificate of certificates) new X509Certificate(certificate);
  } catch {
    throw new Error('The packaged Amazon RDS CA bundle contains an invalid certificate.');
  }

  return bundle;
}

async function connectWithRetry(config: ClientConfig): Promise<Client> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const client = new Client(config);
    try {
      await client.connect();
      return client;
    } catch (error) {
      lastError = error;
      await client.end().catch(() => undefined);
      if (attempt < 5) await new Promise((resolve) => setTimeout(resolve, 1_000 * (attempt + 1)));
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Could not connect to PostgreSQL.');
}

async function inTransaction(client: Client, work: () => Promise<void>): Promise<void> {
  await client.query('BEGIN');
  try {
    await work();
    await client.query('COMMIT');
  } catch (error) {
    try {
      await client.query('ROLLBACK');
    } catch (rollbackError) {
      throw new AggregateError([error, rollbackError], 'Database role bootstrap and rollback both failed.');
    }
    throw error;
  }
}

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Required environment variable ${name} is missing.`);
  return value;
}
