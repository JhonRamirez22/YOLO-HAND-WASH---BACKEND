import * as cdk from 'aws-cdk-lib';
import { HandwashBackendStack } from '../lib/backend-stack.js';

const region = process.env.HANDWASH_AWS_REGION ?? 'sa-east-1';
const account = process.env.CDK_DEFAULT_ACCOUNT;
if (!account) {
  throw new Error('CDK no detectó una sesión AWS. Inicia sesión con el perfil autorizado y vuelve a ejecutar.');
}

const app = new cdk.App();
new HandwashBackendStack(app, 'HandwashJavaBackend', {
  env: { account, region },
  terminationProtection: true,
  alertEmail: process.env.HANDWASH_ALERT_EMAIL?.trim() || undefined,
});
