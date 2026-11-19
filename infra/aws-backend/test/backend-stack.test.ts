import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as nodePath from 'node:path';
import test from 'node:test';
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { HandwashBackendStack } from '../lib/backend-stack.js';

test('cloud pilot keeps the database private and runs one Java session arbiter', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestBackend', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
  });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::RDS::DBInstance', {
    DBName: 'handwash',
    Engine: 'postgres',
    EngineVersion: '18.6',
    MultiAZ: true,
    PubliclyAccessible: false,
    StorageEncrypted: true,
    DeletionProtection: true,
  });
  template.hasResourceProperties('AWS::ECS::Service', {
    DesiredCount: 1,
    DeploymentConfiguration: Match.objectLike({
      MinimumHealthyPercent: 0,
      MaximumPercent: 100,
      DeploymentCircuitBreaker: { Enable: true, Rollback: true },
    }),
  });
  template.hasResourceProperties('AWS::Cognito::UserPool', {
    MfaConfiguration: 'ON',
    // A backend release must preserve the deployed closed-registration policy.
    AdminCreateUserConfig: { AllowAdminCreateUserOnly: true },
    AutoVerifiedAttributes: ['email'],
    EnabledMfas: ['SOFTWARE_TOKEN_MFA'],
  });
  template.hasResourceProperties('AWS::Cognito::UserPoolClient', {
    CallbackURLs: ['https://frontend-proyecto-handwash.vercel.app/'],
    LogoutURLs: ['https://frontend-proyecto-handwash.vercel.app/'],
    GenerateSecret: false,
  });
  const taskDefinition = Object.values(template.findResources('AWS::ECS::TaskDefinition'))[0] as {
    Properties?: { ContainerDefinitions?: Array<{ Environment?: Array<{ Name: string; Value: unknown }> }> };
  };
  const containerDefinition = taskDefinition.Properties?.ContainerDefinitions?.[0];
  assert.ok(containerDefinition);
  const corsOrigins = containerDefinition.Environment
    ?.find((entry) => entry.Name === 'HANDWASH_CORS_ALLOWED_ORIGINS');
  assert.ok(corsOrigins);
  assert.equal(corsOrigins.Value, 'https://frontend-proyecto-handwash.vercel.app');
  const key = Object.values(template.findResources('AWS::KMS::Key'))[0] as {
    Properties: { KeyPolicy: { Statement: Array<Record<string, unknown>> } };
  };
  const logsKeyPermission = key.Properties.KeyPolicy.Statement.find((statement) =>
    statement.Sid === 'AllowCloudWatchLogsUseForStackLogGroups');
  assert.ok(logsKeyPermission);
  assert.equal(
    (logsKeyPermission.Principal as { Service?: string }).Service,
    'logs.sa-east-1.amazonaws.com',
  );
  const logGroupArns = (logsKeyPermission.Condition as {
    ArnEquals: Record<string, unknown[]>;
  }).ArnEquals['kms:EncryptionContext:aws:logs:arn'];
  assert.ok(Array.isArray(logGroupArns) && logGroupArns.length === 6);
  template.hasResourceProperties('AWS::ECR::Repository', {
    ImageScanningConfiguration: { ScanOnPush: true },
    ImageTagMutability: 'IMMUTABLE',
  });
  template.hasResourceProperties('AWS::CodeBuild::Project', {
    Source: Match.objectLike({ Type: 'S3' }),
    Environment: Match.objectLike({ PrivilegedMode: true }),
    TimeoutInMinutes: 45,
  });
  const codeBuildProject = Object.values(template.findResources('AWS::CodeBuild::Project'))[0] as {
    Properties: { Source: { BuildSpec: string } };
  };
  const buildSpec = JSON.parse(codeBuildProject.Properties.Source.BuildSpec) as {
    phases: { build: { commands: string[] } };
  };
  const dockerBuildCommand = buildSpec.phases.build.commands.find((command) => command.includes('docker build'));
  assert.equal(
    dockerBuildCommand,
    'cd /tmp/handwash-image-context && docker build --pull --file backend/Dockerfile --tag "$BACKEND_IMAGE_REPOSITORY:$BACKEND_IMAGE_TAG" .',
  );
  const buildCheckPolicy = Object.values(template.findResources('AWS::IAM::Policy')).find((resource) =>
    JSON.stringify(resource.Properties?.PolicyDocument).includes('codebuild:BatchGetBuilds')) as {
      Properties: { PolicyDocument: { Statement: Array<Record<string, unknown>> } };
    } | undefined;
  assert.ok(buildCheckPolicy);
  const batchGetBuildsStatement = buildCheckPolicy.Properties.PolicyDocument.Statement.find((statement) =>
    (statement.Action as string[]).includes('codebuild:BatchGetBuilds'));
  assert.ok(batchGetBuildsStatement);
  const buildProjectArn = batchGetBuildsStatement.Resource as { 'Fn::GetAtt'?: string[] };
  assert.ok(buildProjectArn['Fn::GetAtt']?.[0]?.includes('BackendImageBuildProject'));
  assert.equal(buildProjectArn['Fn::GetAtt']?.[1], 'Arn');
  const ecrTokenPolicy = Object.values(template.findResources('AWS::IAM::Policy')).find((resource) =>
    JSON.stringify(resource.Properties?.PolicyDocument).includes('ecr:GetAuthorizationToken')) as {
      Properties: { PolicyDocument: { Statement: Array<Record<string, unknown>> } };
    } | undefined;
  assert.ok(ecrTokenPolicy);
  const ecrTokenStatement = ecrTokenPolicy.Properties.PolicyDocument.Statement.find((statement) =>
    (statement.Action as string[]).includes('ecr:GetAuthorizationToken'));
  assert.ok(ecrTokenStatement);
  assert.equal(JSON.stringify(ecrTokenStatement.Resource), '"*"');
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
    DefaultActions: [Match.objectLike({ Type: 'fixed-response', FixedResponseConfig: { StatusCode: '403' } })],
  });
  template.resourceCountIs('AWS::CloudFront::Distribution', 1);
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::ListenerRule', 1);
  template.resourceCountIs('AWS::CloudFormation::CustomResource', 2);
  assert.ok(Object.keys(template.findResources('AWS::EC2::SecurityGroupIngress')).length > 0);
  const databaseResource = Object.values(template.findResources('AWS::RDS::DBInstance'))[0] as {
    DependsOn?: string[];
  };
  assert.ok(databaseResource.DependsOn?.some((dependency) => dependency.includes('BackendImageBuild')));
  const service = Object.values(template.findResources('AWS::ECS::Service'))[0] as {
    DependsOn?: string[];
  };
  assert.ok(service.DependsOn?.some((dependency) => dependency.includes('BackendImageBuild')));
});

test('report photos live in a private KMS bucket the task can use but the kill switch keeps off', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestEvidence', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
  });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::S3::Bucket', {
    PublicAccessBlockConfiguration: {
      BlockPublicAcls: true, BlockPublicPolicy: true, IgnorePublicAcls: true, RestrictPublicBuckets: true,
    },
    BucketEncryption: {
      ServerSideEncryptionConfiguration: [Match.objectLike({
        ServerSideEncryptionByDefault: Match.objectLike({ SSEAlgorithm: 'aws:kms' }),
      })],
    },
    LifecycleConfiguration: {
      Rules: [Match.objectLike({ ExpirationInDays: 37, Prefix: 'reports/', Status: 'Enabled' })],
    },
  });
  template.hasResource('AWS::S3::Bucket', { DeletionPolicy: 'Retain', UpdateReplacePolicy: 'Retain' });
  template.hasResourceProperties('AWS::S3::BucketPolicy', {
    PolicyDocument: Match.objectLike({
      Statement: Match.arrayWith([Match.objectLike({
        Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } },
      })]),
    }),
  });
  template.hasResourceProperties('AWS::ECS::TaskDefinition', {
    ContainerDefinitions: [Match.objectLike({
      Environment: Match.arrayWith([
        { Name: 'HANDWASH_EVIDENCE_CAPTURE_ENABLED', Value: 'false' },
        { Name: 'HANDWASH_EVIDENCE_RETENTION_DAYS', Value: '30' },
      ]),
    })],
  });
});

test('audit seals go to a write-once KMS bucket the task can only put into, keyed by a secret outside the database', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestAuditSeals', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
  });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::S3::Bucket', {
    ObjectLockEnabled: true,
    ObjectLockConfiguration: {
      ObjectLockEnabled: 'Enabled',
      Rule: { DefaultRetention: { Mode: 'GOVERNANCE', Days: 30 } },
    },
    VersioningConfiguration: { Status: 'Enabled' },
  });
  template.hasResourceProperties('AWS::SecretsManager::Secret', {
    GenerateSecretString: Match.objectLike({ PasswordLength: 48 }),
  });
  template.hasResourceProperties('AWS::ECS::TaskDefinition', {
    ContainerDefinitions: [Match.objectLike({
      Secrets: Match.arrayWith([Match.objectLike({ Name: 'HANDWASH_AUDIT_CHAIN_SECRET' })]),
      Environment: Match.arrayWith([Match.objectLike({ Name: 'HANDWASH_AUDIT_SEAL_BUCKET' })]),
    })],
  });
  const statements = Object.values(template.findResources('AWS::IAM::Policy'))
    .flatMap((policy) => (policy.Properties.PolicyDocument.Statement as { Action: string | string[]; Resource: unknown }[]));
  const onSeals = statements.filter((statement) => JSON.stringify(statement.Resource).includes('AuditSealBucket'));
  const actions = onSeals.flatMap((statement) => [statement.Action].flat());
  assert.ok(actions.includes('s3:PutObject'), 'the task may put seals');
  assert.deepEqual(actions.filter((action) => /Delete|Get|List|Retention|LegalHold|BypassGovernance/.test(action)), [],
    'the task can neither read, delete nor shorten the retention of seals');
});

test('alarms stay inside the free tier, notify the topic and cover every OPS_ALERT code of the Java backend', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestAlarms', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
    alertEmail: 'ops@example.org',
  });
  const template = Template.fromStack(stack);

  const alarms = template.findResources('AWS::CloudWatch::Alarm');
  assert.ok(Object.keys(alarms).length <= 10, 'the CloudWatch free tier covers 10 alarms');
  assert.equal(Object.keys(alarms).length, 9);
  for (const alarm of Object.values(alarms)) {
    assert.ok(JSON.stringify(alarm.Properties.AlarmActions).includes('AlertTopic'), 'every alarm notifies the topic');
    assert.ok(JSON.stringify(alarm.Properties.OKActions).includes('AlertTopic'), 'and announces recovery');
    assert.ok(String(alarm.Properties.AlarmDescription).includes('RUNBOOK_INCIDENTES'), 'and points to the runbook');
  }
  template.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'email', Endpoint: 'ops@example.org' });
  template.hasResourceProperties('AWS::CloudWatch::Alarm', {
    MetricName: 'HealthyHostCount', ComparisonOperator: 'LessThanThreshold', TreatMissingData: 'breaching',
  });

  const filters = Object.values(template.findResources('AWS::Logs::MetricFilter'))
    .map((filter) => String(filter.Properties.FilterPattern));
  assert.ok(filters.length <= 10, 'metric filters stay inside the free custom metrics');
  const javaSource = readFileSync(nodePath.resolve(
    __dirname, '../../../backend/src/main/java/com/handwash/ops/OpsAlert.java'), 'utf8');
  const codes = [...javaSource.matchAll(/^\s{8}([A-Z_]+),?$/gm)].map((match) => match[1]);
  assert.ok(codes.length >= 6, 'the OpsAlert enum was found');
  for (const code of codes) {
    assert.ok(filters.some((pattern) => pattern.includes(`OPS_ALERT code=${code}"`)), `no metric filter for ${code}`);
  }
});

test('without an alert e-mail the topic exists but nobody is subscribed yet', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestNoEmail', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
  });
  const template = Template.fromStack(stack);
  template.resourceCountIs('AWS::SNS::Topic', 1);
  template.resourceCountIs('AWS::SNS::Subscription', 0);
});

test('a replacement task receives traffic within 20 s of passing its health check (RTO budget)', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const template = Template.fromStack(new HandwashBackendStack(app, 'TestRto', {
    env: { account: '123456789012', region: 'sa-east-1' }, cloudFrontOriginFacingPrefixList: prefixList,
  }));
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
    HealthCheckIntervalSeconds: 10, HealthyThresholdCount: 2, UnhealthyThresholdCount: 3, HealthCheckPath: '/actuator/health',
  });
});

test('the ALB only serves requests that carry the secret origin header and nothing is open to the whole internet', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const template = Template.fromStack(new HandwashBackendStack(app, 'TestOrigin', {
    env: { account: '123456789012', region: 'sa-east-1' }, cloudFrontOriginFacingPrefixList: prefixList,
  }));

  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
    DefaultActions: [Match.objectLike({ Type: 'fixed-response', FixedResponseConfig: Match.objectLike({ StatusCode: '403' }) })],
  });
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::ListenerRule', {
    Conditions: [Match.objectLike({ Field: 'http-header', HttpHeaderConfig: Match.objectLike({ HttpHeaderName: 'X-Handwash-Origin' }) })],
  });
  template.hasResourceProperties('AWS::CloudFront::Distribution', {
    DistributionConfig: Match.objectLike({
      Origins: [Match.objectLike({ OriginCustomHeaders: [Match.objectLike({ HeaderName: 'X-Handwash-Origin' })] })],
      DefaultCacheBehavior: Match.objectLike({ ViewerProtocolPolicy: 'redirect-to-https' }),
    }),
  });
  const ingress = JSON.stringify([
    ...Object.values(template.findResources('AWS::EC2::SecurityGroup')).map((group) => group.Properties.SecurityGroupIngress ?? []),
    ...Object.values(template.findResources('AWS::EC2::SecurityGroupIngress')).map((rule) => rule.Properties),
  ]);
  assert.ok(!ingress.includes('0.0.0.0/0'), 'no security group accepts traffic from the whole internet');
  assert.ok(ingress.includes('SourcePrefixListId'), 'the ALB accepts only the CloudFront prefix list');
});

test('the task role gets only the S3 actions it uses on the photo bucket', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const template = Template.fromStack(new HandwashBackendStack(app, 'TestPhotoIam', {
    env: { account: '123456789012', region: 'sa-east-1' }, cloudFrontOriginFacingPrefixList: prefixList,
  }));
  const statements = Object.values(template.findResources('AWS::IAM::Policy'))
    .flatMap((policy) => (policy.Properties.PolicyDocument.Statement as { Action: string | string[]; Resource: unknown }[]));
  const onPhotos = statements.filter((statement) => JSON.stringify(statement.Resource).includes('WashReportEvidenceBucket'));
  const actions = onPhotos.flatMap((statement) => [statement.Action].flat()).sort();
  assert.deepEqual(actions, ['s3:DeleteObject', 's3:GetObject', 's3:PutObject']);
});

test('operators sign in through their own pool without MFA while ADMIN keeps mandatory MFA', () => {
  const app = new cdk.App();
  const prefixList = ec2.PrefixList.fromPrefixListId(app, 'CloudFrontPrefixList', 'pl-0123456789abcdef0');
  const stack = new HandwashBackendStack(app, 'TestOperatorPool', {
    env: { account: '123456789012', region: 'sa-east-1' },
    cloudFrontOriginFacingPrefixList: prefixList,
  });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::Cognito::UserPool', { UserPoolName: 'handwash-cloud-pilot', MfaConfiguration: 'ON' });
  template.hasResourceProperties('AWS::Cognito::UserPool', {
    UserPoolName: 'handwash-operators',
    MfaConfiguration: 'OFF',
    AdminCreateUserConfig: Match.objectLike({ AllowAdminCreateUserOnly: true }),
  });
  template.hasResourceProperties('AWS::ECS::TaskDefinition', {
    ContainerDefinitions: [Match.objectLike({
      Environment: Match.arrayWith([
        Match.objectLike({ Name: 'OPERATOR_ISSUER_URI' }),
        Match.objectLike({ Name: 'OPERATOR_CLIENT_ID' }),
        Match.objectLike({ Name: 'OPERATOR_USER_POOL_ID' }),
      ]),
    })],
  });
  const statements = Object.values(template.findResources('AWS::IAM::Policy'))
    .flatMap((policy) => (policy.Properties.PolicyDocument.Statement as { Action: string | string[]; Resource: unknown }[]));
  const cognitoActions = statements.flatMap((statement) => [statement.Action].flat())
    .filter((action) => action.startsWith('cognito-idp:'));
  assert.deepEqual([...new Set(cognitoActions)].sort(),
    ['cognito-idp:AdminCreateUser', 'cognito-idp:AdminDisableUser', 'cognito-idp:AdminUserGlobalSignOut'],
    'the task can create and disable operator logins and nothing else in Cognito');
  const onCognito = statements.filter((statement) => [statement.Action].flat().some((a) => a.startsWith('cognito-idp:')));
  assert.ok(onCognito.every((statement) => JSON.stringify(statement.Resource).includes('OperatorPool')),
    'limited to the operator pool ARN');
});
