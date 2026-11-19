import * as path from 'node:path';
import * as cdk from 'aws-cdk-lib';
import { Duration, RemovalPolicy, Stack } from 'aws-cdk-lib';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cloudwatchActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as nodejs from 'aws-cdk-lib/aws-lambda-nodejs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSubscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as s3Assets from 'aws-cdk-lib/aws-s3-assets';
import * as custom from 'aws-cdk-lib/custom-resources';
import { Construct } from 'constructs';

export interface HandwashBackendStackProps extends cdk.StackProps {
  readonly cloudFrontOriginFacingPrefixList?: ec2.IPrefixList;
  /** Address that receives alarm e-mails. SNS asks for a one-time confirmation click. */
  readonly alertEmail?: string;
}

const MODEL_VERSION = 'yolo26n_7pasos_b64-unvalidated';
const DATASET_VERSION = 'training-yaml-not-provided';
const MODEL_SHA256 = '9c1198467ef84c108b093f2c3da8d73d313b96855e2d42ec5f4b9729577d30e7';
const EVIDENCE_RETENTION_DAYS = 30;
const AUDIT_SEAL_RETENTION_DAYS = 30;
const VERCEL_FRONTEND_ORIGIN = 'https://frontend-proyecto-handwash.vercel.app';
const IMAGE_BUILD_PIPELINE_REVISION = '1';
const ENCRYPTED_LOG_GROUP_NAMES = [
  '/aws/rds/instance/handwash-cloud-pilot-pg/postgresql',
  '/ecs/handwash-java-backend',
  '/codebuild/handwash-java-backend-image',
  '/aws/lambda/handwash-db-role-bootstrap',
  '/aws/lambda/handwash-image-build-start',
  '/aws/lambda/handwash-image-build-check',
];

export class HandwashBackendStack extends Stack {
  constructor(scope: Construct, id: string, props: HandwashBackendStackProps) {
    super(scope, id, props);
    const cloudFrontPrefixList = props.cloudFrontOriginFacingPrefixList
      ?? ec2.PrefixList.fromLookup(this, 'CloudFrontOriginFacingPrefixList', {
        prefixListName: 'com.amazonaws.global.cloudfront.origin-facing',
      });

    const backendSource = new s3Assets.Asset(this, 'BackendImageSource', {
      path: path.resolve(__dirname, '../../../backend'),
      exclude: [
        '.DS_Store',
        '.runtime/**',
        'models/**',
        'target/**',
        'requirements.txt',
        '**/*.pt',
        '**/*.onnx',
        '**/*.engine',
        '**/*.mp4',
        '**/*.avi',
        '**/*.mov',
        '**/*.jpg',
        '**/*.jpeg',
        '**/*.png',
      ],
    });

    const dataKey = new kms.Key(this, 'DataKey', {
      alias: 'alias/handwash-cloud-pilot',
      enableKeyRotation: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    dataKey.addToResourcePolicy(new iam.PolicyStatement({
      sid: 'AllowCloudWatchLogsUseForStackLogGroups',
      principals: [new iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)],
      actions: [
        'kms:Encrypt',
        'kms:Decrypt',
        'kms:ReEncrypt*',
        'kms:GenerateDataKey*',
        'kms:Describe*',
      ],
      resources: ['*'],
      conditions: {
        ArnEquals: {
          'kms:EncryptionContext:aws:logs:arn': ENCRYPTED_LOG_GROUP_NAMES.map((name) =>
            this.formatArn({
              service: 'logs',
              resource: 'log-group',
              resourceName: name,
              arnFormat: cdk.ArnFormat.COLON_RESOURCE_NAME,
            })),
        },
      },
    }));
    const vpc = new ec2.Vpc(this, 'BackendVpc', {
      maxAzs: 2,
      natGateways: 2,
      subnetConfiguration: [
        { name: 'Public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'Private', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 20 },
      ],
    });

    new logs.LogGroup(this, 'DatabaseLogs', {
      logGroupName: '/aws/rds/instance/handwash-cloud-pilot-pg/postgresql',
      retention: logs.RetentionDays.ONE_MONTH,
      encryptionKey: dataKey,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const databaseSecurityGroup = new ec2.SecurityGroup(this, 'DatabaseSecurityGroup', {
      vpc,
      description: 'PostgreSQL accepts connections only from Java and its one-time role bootstrap.',
      allowAllOutbound: true,
    });

    const runtimeSecret = new secretsmanager.Secret(this, 'RuntimeDatabaseSecret', {
      secretName: 'handwash/cloud/database/runtime',
      encryptionKey: dataKey,
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'handwash_runtime' }),
        generateStringKey: 'password',
        passwordLength: 48,
        excludePunctuation: true,
      },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const migrationSecret = new secretsmanager.Secret(this, 'MigrationDatabaseSecret', {
      secretName: 'handwash/cloud/database/migrator',
      encryptionKey: dataKey,
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'handwash_migrator' }),
        generateStringKey: 'password',
        passwordLength: 48,
        excludePunctuation: true,
      },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const originVerificationSecret = new secretsmanager.Secret(this, 'OriginVerificationSecret', {
      secretName: 'handwash/cloud/alb-origin-verification',
      encryptionKey: dataKey,
      generateSecretString: { passwordLength: 48, excludePunctuation: true },
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const postgresEngine = rds.DatabaseInstanceEngine.postgres({
      version: rds.PostgresEngineVersion.of('18.6', '18'),
    });
    const database = new rds.DatabaseInstance(this, 'AuditDatabase', {
      instanceIdentifier: 'handwash-cloud-pilot-pg',
      engine: postgresEngine,
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.M7G, ec2.InstanceSize.LARGE),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [databaseSecurityGroup],
      credentials: rds.Credentials.fromGeneratedSecret('handwash_db_owner', {
        secretName: 'handwash/cloud/database/bootstrap-owner',
        encryptionKey: dataKey,
      }),
      databaseName: 'handwash',
      port: 5432,
      multiAz: true,
      allocatedStorage: 100,
      maxAllocatedStorage: 500,
      storageType: rds.StorageType.GP3,
      storageEncrypted: true,
      storageEncryptionKey: dataKey,
      backupRetention: Duration.days(7),
      deletionProtection: true,
      publiclyAccessible: false,
      cloudwatchLogsExports: ['postgresql'],
      cloudwatchLogsRetention: logs.RetentionDays.ONE_MONTH,
      performanceInsightRetention: rds.PerformanceInsightRetention.DEFAULT,
      performanceInsightEncryptionKey: dataKey,
      parameterGroup: new rds.ParameterGroup(this, 'PostgresTlsParameters', {
        engine: postgresEngine,
        parameters: { 'rds.force_ssl': '1' },
      }),
      autoMinorVersionUpgrade: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const backendRepository = new ecr.Repository(this, 'BackendImageRepository', {
      repositoryName: 'handwash-java-backend',
      encryption: ecr.RepositoryEncryption.KMS,
      encryptionKey: dataKey,
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 5, description: 'Retain the five latest backend releases.' }],
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const buildLogs = new logs.LogGroup(this, 'BackendImageBuildLogs', {
      logGroupName: '/codebuild/handwash-java-backend-image',
      retention: logs.RetentionDays.ONE_MONTH,
      encryptionKey: dataKey,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const imageTag = backendSource.assetHash;
    const imageProject = new codebuild.Project(this, 'BackendImageBuildProject', {
      projectName: 'handwash-java-backend-image',
      description: 'Builds only the Java backend container from the curated source asset.',
      source: codebuild.Source.s3({ bucket: backendSource.bucket, path: backendSource.s3ObjectKey }),
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_7_0,
        computeType: codebuild.ComputeType.MEDIUM,
        privileged: true,
        environmentVariables: {
          BACKEND_IMAGE_REPOSITORY: { value: backendRepository.repositoryUri },
          BACKEND_IMAGE_TAG: { value: imageTag },
        },
      },
      buildSpec: codebuild.BuildSpec.fromObject({
        version: '0.2',
        phases: {
          pre_build: {
            commands: [
              'aws ecr get-login-password --region "$AWS_DEFAULT_REGION" | docker login --username AWS --password-stdin "$BACKEND_IMAGE_REPOSITORY"',
            ],
          },
          build: {
            commands: [
              'if find "$CODEBUILD_SRC_DIR" -type f \\( -name "*.pt" -o -name "*.onnx" -o -name "*.engine" -o -name "*.mp4" -o -name "*.avi" -o -name "*.mov" \\) -print -quit | grep -q .; then echo "Unexpected model or media file in Java source asset"; exit 1; fi',
              'mkdir -p /tmp/handwash-image-context/backend',
              'cp Dockerfile pom.xml /tmp/handwash-image-context/backend/',
              'cp -R src /tmp/handwash-image-context/backend/',
              'cd /tmp/handwash-image-context && docker build --pull --file backend/Dockerfile --tag "$BACKEND_IMAGE_REPOSITORY:$BACKEND_IMAGE_TAG" .',
              'docker push "$BACKEND_IMAGE_REPOSITORY:$BACKEND_IMAGE_TAG"',
            ],
          },
        },
      }),
      timeout: Duration.minutes(45),
      queuedTimeout: Duration.minutes(10),
      concurrentBuildLimit: 1,
      grantReportGroupPermissions: false,
      logging: { cloudWatch: { logGroup: buildLogs, prefix: 'image' } },
    });
    imageProject.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ecr:GetAuthorizationToken'],
      resources: ['*'],
    }));
    backendRepository.grantPullPush(imageProject);

    const imageBuildStartHandler = new nodejs.NodejsFunction(this, 'BackendImageBuildStartHandler', {
      entry: path.join(__dirname, '../lambda/backend-image-build.ts'),
      handler: 'startBuild',
      runtime: lambda.Runtime.NODEJS_22_X,
      memorySize: 256,
      timeout: Duration.seconds(30),
      environment: { CODEBUILD_PROJECT_NAME: imageProject.projectName },
      logGroup: new logs.LogGroup(this, 'BackendImageBuildStartLogs', {
        logGroupName: '/aws/lambda/handwash-image-build-start',
        retention: logs.RetentionDays.ONE_MONTH,
        encryptionKey: dataKey,
        removalPolicy: RemovalPolicy.RETAIN,
      }),
      bundling: { minify: true, sourceMap: false },
    });
    imageBuildStartHandler.addToRolePolicy(new iam.PolicyStatement({
      actions: ['codebuild:StartBuild'],
      resources: [imageProject.projectArn],
    }));
    const imageBuildCheckHandler = new nodejs.NodejsFunction(this, 'BackendImageBuildCheckHandler', {
      entry: path.join(__dirname, '../lambda/backend-image-build.ts'),
      handler: 'checkBuild',
      runtime: lambda.Runtime.NODEJS_22_X,
      memorySize: 256,
      timeout: Duration.seconds(30),
      logGroup: new logs.LogGroup(this, 'BackendImageBuildCheckLogs', {
        logGroupName: '/aws/lambda/handwash-image-build-check',
        retention: logs.RetentionDays.ONE_MONTH,
        encryptionKey: dataKey,
        removalPolicy: RemovalPolicy.RETAIN,
      }),
      bundling: { minify: true, sourceMap: false },
    });
    imageBuildCheckHandler.addToRolePolicy(new iam.PolicyStatement({
      actions: ['codebuild:BatchGetBuilds'],
      resources: [imageProject.projectArn],
    }));
    const imageBuildProvider = new custom.Provider(this, 'BackendImageBuildProvider', {
      onEventHandler: imageBuildStartHandler,
      isCompleteHandler: imageBuildCheckHandler,
      queryInterval: Duration.seconds(30),
      totalTimeout: Duration.minutes(60),
    });
    const imageBuild = new cdk.CustomResource(this, 'BackendImageBuild', {
      serviceToken: imageBuildProvider.serviceToken,
      properties: {
        ProjectName: imageProject.projectName,
        ImageTag: imageTag,
        PipelineRevision: IMAGE_BUILD_PIPELINE_REVISION,
      },
    });
    database.node.addDependency(imageBuild);

    const bootstrapSecurityGroup = new ec2.SecurityGroup(this, 'DatabaseBootstrapSecurityGroup', {
      vpc,
      description: 'One-time database role bootstrap function.',
      allowAllOutbound: true,
    });
    database.connections.allowDefaultPortFrom(bootstrapSecurityGroup, 'Database role bootstrap');
    const bootstrapHandler = new nodejs.NodejsFunction(this, 'DatabaseRoleBootstrapHandler', {
      entry: path.join(__dirname, '../lambda/database-role-bootstrap.ts'),
      handler: 'handler',
      runtime: lambda.Runtime.NODEJS_22_X,
      architecture: lambda.Architecture.ARM_64,
      memorySize: 512,
      timeout: Duration.minutes(3),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [bootstrapSecurityGroup],
      logGroup: new logs.LogGroup(this, 'DatabaseBootstrapLogs', {
        logGroupName: '/aws/lambda/handwash-db-role-bootstrap',
        retention: logs.RetentionDays.ONE_MONTH,
        encryptionKey: dataKey,
        removalPolicy: RemovalPolicy.RETAIN,
      }),
      environment: {
        DB_HOST: database.dbInstanceEndpointAddress,
        DB_PORT: database.dbInstanceEndpointPort,
        DB_NAME: 'handwash',
        DB_OWNER_SECRET_ARN: database.secret!.secretArn,
        DB_RUNTIME_SECRET_ARN: runtimeSecret.secretArn,
        DB_MIGRATION_SECRET_ARN: migrationSecret.secretArn,
      },
      bundling: {
        minify: true,
        sourceMap: false,
        commandHooks: {
          beforeBundling: () => [],
          beforeInstall: () => [],
          afterBundling: (inputDir, outputDir) => [
            `mkdir -p "${outputDir}/certs" && cp "${inputDir}/lambda/certs/global-bundle.pem" "${outputDir}/certs/global-bundle.pem"`,
          ],
        },
      },
    });
    database.secret!.grantRead(bootstrapHandler);
    runtimeSecret.grantRead(bootstrapHandler);
    migrationSecret.grantRead(bootstrapHandler);
    const bootstrapProvider = new custom.Provider(this, 'DatabaseRoleBootstrapProvider', {
      onEventHandler: bootstrapHandler,
    });
    const databaseRoles = new cdk.CustomResource(this, 'DatabaseRoleBootstrap', {
      serviceToken: bootstrapProvider.serviceToken,
      properties: { revision: '1' },
    });
    databaseRoles.node.addDependency(database);

    const userPool = new cognito.UserPool(this, 'UserPool', {
      userPoolName: 'handwash-cloud-pilot',
      selfSignUpEnabled: false,
      autoVerify: { email: true },
      signInAliases: { email: true },
      mfa: cognito.Mfa.REQUIRED,
      mfaSecondFactor: { sms: false, otp: true },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      passwordPolicy: {
        minLength: 14,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
        tempPasswordValidity: Duration.days(1),
      },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    new cognito.CfnUserPoolGroup(this, 'OperatorGroup', {
      userPoolId: userPool.userPoolId,
      groupName: 'OPERATOR',
      description: 'Inicia sesiones de lavado no clínicas.',
    });
    new cognito.CfnUserPoolGroup(this, 'AdminGroup', {
      userPoolId: userPool.userPoolId,
      groupName: 'ADMIN',
      description: 'Consulta métricas y auditoría.',
    });
    userPool.addDomain('ManagedLoginDomain', {
      cognitoDomain: { domainPrefix: `handwash-pilot-${this.account}` },
    });
    const producerScope = new cognito.ResourceServerScope({
      scopeName: 'producer',
      scopeDescription: 'Publica observaciones del productor autorizado.',
    });
    const producerResourceServer = userPool.addResourceServer('ProducerResourceServer', {
      identifier: 'handwash',
      scopes: [producerScope],
    });

    const albSecurityGroup = new ec2.SecurityGroup(this, 'LoadBalancerSecurityGroup', {
      vpc,
      description: 'Only CloudFront origin-facing edges may reach the HTTP origin.',
      allowAllOutbound: true,
    });
    albSecurityGroup.addIngressRule(
      cloudFrontPrefixList,
      ec2.Port.tcp(80),
      'CloudFront origin-facing managed prefix list only',
    );
    const alb = new elbv2.ApplicationLoadBalancer(this, 'BackendLoadBalancer', {
      vpc,
      internetFacing: true,
      securityGroup: albSecurityGroup,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });
    const targetGroup = new elbv2.ApplicationTargetGroup(this, 'BackendTargets', {
      vpc,
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      healthCheck: {
        path: '/actuator/health',
        healthyHttpCodes: '200',
        // 10 s instead of 30 s: a replacement task takes 20 s instead of 60 s to receive traffic (R-12).
        interval: Duration.seconds(10),
        timeout: Duration.seconds(5),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    });
    const originHeaderValue = originVerificationSecret.secretValue.unsafeUnwrap();
    const listener = alb.addListener('HttpListener', {
      port: 80,
      open: false,
      defaultAction: elbv2.ListenerAction.fixedResponse(403, {
        contentType: 'text/plain',
        messageBody: 'Forbidden',
      }),
    });
    listener.addAction('CloudFrontOriginOnly', {
      priority: 1,
      conditions: [elbv2.ListenerCondition.httpHeader('X-Handwash-Origin', [originHeaderValue])],
      action: elbv2.ListenerAction.forward([targetGroup]),
    });

    const distribution = new cloudfront.Distribution(this, 'BackendHttpsDistribution', {
      comment: 'HTTPS/WSS entry point for the Java backend; API traffic is never cached.',
      defaultBehavior: {
        origin: new origins.HttpOrigin(alb.loadBalancerDnsName, {
          httpPort: 80,
          protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
          customHeaders: { 'X-Handwash-Origin': originHeaderValue },
          readTimeout: Duration.seconds(60),
          keepaliveTimeout: Duration.seconds(60),
        }),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachedMethods: cloudfront.CachedMethods.CACHE_GET_HEAD_OPTIONS,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
        compress: true,
      },
    });

    const spaClient = userPool.addClient('SpaClient', {
      userPoolClientName: 'handwash-web-spa',
      generateSecret: false,
      enableTokenRevocation: true,
      preventUserExistenceErrors: true,
      authFlows: { userSrp: true },
      accessTokenValidity: Duration.minutes(15),
      idTokenValidity: Duration.minutes(15),
      refreshTokenValidity: Duration.days(1),
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL],
        callbackUrls: [`${VERCEL_FRONTEND_ORIGIN}/`],
        logoutUrls: [`${VERCEL_FRONTEND_ORIGIN}/`],
      },
    });
    // Operators sign in with an account the ADMIN creates, without MFA. A separate pool keeps MFA
    // mandatory for ADMIN and lets Java accept operator tokens only for /api/v1/operator/**.
    const operatorPool = new cognito.UserPool(this, 'OperatorPool', {
      userPoolName: 'handwash-operators',
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      mfa: cognito.Mfa.OFF,
      accountRecovery: cognito.AccountRecovery.NONE,
      passwordPolicy: {
        minLength: 12,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
        tempPasswordValidity: Duration.days(1),
      },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    operatorPool.addDomain('OperatorManagedLoginDomain', {
      cognitoDomain: { domainPrefix: `handwash-operators-${this.account}` },
    });
    const operatorClient = operatorPool.addClient('OperatorSpaClient', {
      userPoolClientName: 'handwash-operator-spa',
      generateSecret: false,
      enableTokenRevocation: true,
      preventUserExistenceErrors: true,
      authFlows: { userSrp: true },
      accessTokenValidity: Duration.minutes(15),
      idTokenValidity: Duration.minutes(15),
      refreshTokenValidity: Duration.days(1),
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL],
        callbackUrls: [`${VERCEL_FRONTEND_ORIGIN}/`],
        logoutUrls: [`${VERCEL_FRONTEND_ORIGIN}/`],
      },
    });
    const producerClient = userPool.addClient('ProducerClient', {
      userPoolClientName: 'handwash-rtx-producer',
      generateSecret: true,
      preventUserExistenceErrors: true,
      oAuth: {
        flows: { clientCredentials: true },
        scopes: [cognito.OAuthScope.resourceServer(producerResourceServer, producerScope)],
      },
    });
    producerClient.node.addDependency(producerResourceServer);

    const appLogs = new logs.LogGroup(this, 'BackendLogs', {
      logGroupName: '/ecs/handwash-java-backend',
      retention: logs.RetentionDays.ONE_MONTH,
      encryptionKey: dataKey,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const appSecurityGroup = new ec2.SecurityGroup(this, 'BackendTaskSecurityGroup', {
      vpc,
      description: 'Spring Boot tasks accept app traffic only from the ALB.',
      allowAllOutbound: true,
    });
    appSecurityGroup.addIngressRule(albSecurityGroup, ec2.Port.tcp(8080), 'ALB to Java backend');
    database.connections.allowDefaultPortFrom(appSecurityGroup, 'Java backend PostgreSQL access');

    const ecsTaskPrincipal = new iam.ServicePrincipal('ecs-tasks.amazonaws.com').withConditions({
      StringEquals: { 'aws:SourceAccount': this.account },
      ArnLike: { 'aws:SourceArn': `arn:${this.partition}:ecs:${this.region}:${this.account}:*` },
    });
    const executionRole = new iam.Role(this, 'BackendExecutionRole', {
      assumedBy: ecsTaskPrincipal,
      description: 'Pulls the Java image, writes its log stream, and loads only injected DB secrets.',
    });
    executionRole.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
    );
    const taskRole = new iam.Role(this, 'BackendTaskRole', {
      assumedBy: ecsTaskPrincipal,
      description: 'Java backend: only reads and writes report photos in the private evidence bucket.',
    });
    // Photos of REPORTADO attempts: private, SSE-KMS, enforced TLS, expired by Java at the
    // retention end and by this lifecycle rule a week later as a backstop for orphaned objects.
    const evidenceBucket = new s3.Bucket(this, 'WashReportEvidenceBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.KMS,
      encryptionKey: dataKey,
      bucketKeyEnabled: true,
      enforceSSL: true,
      versioned: false,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      removalPolicy: RemovalPolicy.RETAIN,
      lifecycleRules: [{
        id: 'ExpireReportPhotos',
        prefix: 'reports/',
        expiration: Duration.days(EVIDENCE_RETENTION_DAYS + 7),
        abortIncompleteMultipartUploadAfter: Duration.days(1),
      }],
    });
    // Exactly what S3EvidenceStore does (put, get, delete one photo); grantReadWrite would also allow listing,
    // retention and legal-hold edits that nothing here needs.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject', 's3:GetObject', 's3:DeleteObject'],
      resources: [evidenceBucket.arnForObjects('reports/*')],
    }));

    // Create operator logins and cut them off; nothing else in Cognito, and only in the operator pool.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['cognito-idp:AdminCreateUser', 'cognito-idp:AdminDisableUser', 'cognito-idp:AdminUserGlobalSignOut'],
      resources: [operatorPool.userPoolArn],
    }));

    // Daily audit seals: write-once copies outside the database. Governance Object Lock keeps each
    // object undeletable for the retention period; the task may only put, never read or delete.
    const auditSealBucket = new s3.Bucket(this, 'AuditSealBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.KMS,
      encryptionKey: dataKey,
      bucketKeyEnabled: true,
      enforceSSL: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      versioned: true,
      objectLockEnabled: true,
      objectLockDefaultRetention: s3.ObjectLockRetention.governance(Duration.days(AUDIT_SEAL_RETENTION_DAYS)),
      removalPolicy: RemovalPolicy.RETAIN,
      lifecycleRules: [{
        id: 'ExpireSeals',
        prefix: 'seals/',
        expiration: Duration.days(AUDIT_SEAL_RETENTION_DAYS + 7),
        noncurrentVersionExpiration: Duration.days(AUDIT_SEAL_RETENTION_DAYS + 7),
      }],
    });
    // Exactly s3:PutObject: grantPut would also allow editing legal holds and retention.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject'],
      resources: [auditSealBucket.arnForObjects('seals/*')],
    }));
    // One KMS grant for both buckets: S3 uses the key on the task role's behalf (SSE-KMS on write, decrypt on read).
    dataKey.grant(taskRole, 'kms:Decrypt', 'kms:Encrypt', 'kms:GenerateDataKey');
    // Keyed outside the database: whoever can write the tables cannot recompute the audit chain.
    const auditChainSecret = new secretsmanager.Secret(this, 'AuditChainSecret', {
      description: 'HMAC key of the audit hash chain and the daily seals. Not stored in the database.',
      generateSecretString: { passwordLength: 48, excludePunctuation: true },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const taskDefinition = new ecs.FargateTaskDefinition(this, 'BackendTaskDefinition', {
      cpu: 1024,
      memoryLimitMiB: 2048,
      executionRole,
      taskRole,
      runtimePlatform: {
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
      },
    });
    const appContainer = taskDefinition.addContainer('JavaBackend', {
      image: ecs.ContainerImage.fromEcrRepository(backendRepository, imageTag),
      logging: new ecs.AwsLogDriver({ streamPrefix: 'java', logGroup: appLogs }),
      environment: {
        SPRING_PROFILES_ACTIVE: 'cloud',
        SERVER_PORT: '8080',
        SERVER_ADDRESS: '0.0.0.0',
        HANDWASH_DB_POOL_MAX: '8',
        HANDWASH_MODEL_VERSION: MODEL_VERSION,
        HANDWASH_MODEL_SHA256: MODEL_SHA256,
        HANDWASH_DATASET_VERSION: DATASET_VERSION,
        HANDWASH_CORS_ALLOWED_ORIGINS: VERCEL_FRONTEND_ORIGIN,
        // Off until the university consent and notice are in place; ADMIN also holds a runtime switch.
        HANDWASH_EVIDENCE_CAPTURE_ENABLED: 'false',
        HANDWASH_EVIDENCE_BUCKET: evidenceBucket.bucketName,
        HANDWASH_EVIDENCE_KMS_KEY_ID: dataKey.keyArn,
        HANDWASH_EVIDENCE_RETENTION_DAYS: String(EVIDENCE_RETENTION_DAYS),
        HANDWASH_AUDIT_SEAL_BUCKET: auditSealBucket.bucketName,
        HANDWASH_AUDIT_SEAL_KMS_KEY_ID: dataKey.keyArn,
        COGNITO_ISSUER_URI: `https://cognito-idp.${this.region}.amazonaws.com/${userPool.userPoolId}`,
        COGNITO_CLIENT_ID: spaClient.userPoolClientId,
        COGNITO_PRODUCER_CLIENT_ID: producerClient.userPoolClientId,
        OPERATOR_ISSUER_URI: `https://cognito-idp.${this.region}.amazonaws.com/${operatorPool.userPoolId}`,
        OPERATOR_CLIENT_ID: operatorClient.userPoolClientId,
        OPERATOR_USER_POOL_ID: operatorPool.userPoolId,
        HANDWASH_DB_URL: `jdbc:postgresql://${database.dbInstanceEndpointAddress}:5432/handwash?sslmode=verify-full&sslrootcert=/etc/ssl/certs/aws-rds-global-bundle.pem`,
        HANDWASH_MIGRATION_DB_URL: `jdbc:postgresql://${database.dbInstanceEndpointAddress}:5432/handwash?sslmode=verify-full&sslrootcert=/etc/ssl/certs/aws-rds-global-bundle.pem`,
      },
      healthCheck: {
        command: ['CMD-SHELL', 'curl --fail --silent http://localhost:8080/actuator/health || exit 1'],
        interval: Duration.seconds(30),
        timeout: Duration.seconds(5),
        retries: 3,
        startPeriod: Duration.seconds(120),
      },
      stopTimeout: Duration.seconds(60),
    });
    appContainer.addPortMappings({ containerPort: 8080, protocol: ecs.Protocol.TCP });
    appContainer.addSecret('HANDWASH_AUDIT_CHAIN_SECRET', ecs.Secret.fromSecretsManager(auditChainSecret));
    appContainer.addSecret('HANDWASH_DB_USERNAME', ecs.Secret.fromSecretsManager(runtimeSecret, 'username'));
    appContainer.addSecret('HANDWASH_DB_PASSWORD', ecs.Secret.fromSecretsManager(runtimeSecret, 'password'));
    appContainer.addSecret('HANDWASH_MIGRATION_DB_USERNAME', ecs.Secret.fromSecretsManager(migrationSecret, 'username'));
    appContainer.addSecret('HANDWASH_MIGRATION_DB_PASSWORD', ecs.Secret.fromSecretsManager(migrationSecret, 'password'));
    backendRepository.grantPull(executionRole);

    const cluster = new ecs.Cluster(this, 'BackendCluster', { vpc });
    const service = new ecs.FargateService(this, 'BackendFargateService', {
      cluster,
      taskDefinition,
      desiredCount: 1,
      assignPublicIp: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [appSecurityGroup],
      minHealthyPercent: 0,
      maxHealthyPercent: 100,
      circuitBreaker: { rollback: true },
      healthCheckGracePeriod: Duration.minutes(3),
      enableExecuteCommand: false,
      platformVersion: ecs.FargatePlatformVersion.LATEST,
    });
    service.node.addDependency(databaseRoles);
    service.node.addDependency(imageBuild);
    targetGroup.addTarget(service);

    // ---- Alarms (R-15). Nine in total: the CloudWatch free tier covers ten, and the four metric
    // filters below stay inside its ten free custom metrics. Names of OPS_ALERT codes are shared with
    // backend/.../ops/OpsAlert.java and checked by a test.
    const alertTopic = new sns.Topic(this, 'AlertTopic', {
      displayName: 'HandWash alertas operativas',
      enforceSSL: true,
    });
    if (props.alertEmail) alertTopic.addSubscription(new snsSubscriptions.EmailSubscription(props.alertEmail));
    const notify = new cloudwatchActions.SnsAction(alertTopic);
    const alarm = (id: string, metric: cloudwatch.IMetric, threshold: number,
      comparison: cloudwatch.ComparisonOperator, evaluationPeriods: number, description: string,
      treatMissingData = cloudwatch.TreatMissingData.NOT_BREACHING): cloudwatch.Alarm => {
      const created = new cloudwatch.Alarm(this, id, {
        metric, threshold, comparisonOperator: comparison, evaluationPeriods, datapointsToAlarm: evaluationPeriods,
        alarmDescription: `${description} Ver docs/calidad/RUNBOOK_INCIDENTES.md`, treatMissingData,
      });
      created.addAlarmAction(notify);
      created.addOkAction(notify);
      return created;
    };
    const ops = (code: string, metricName: string, pattern: string) => {
      new logs.MetricFilter(this, `${metricName}Filter`, {
        logGroup: appLogs,
        filterPattern: logs.FilterPattern.literal(pattern),
        metricNamespace: 'HandWash/Ops',
        metricName,
        metricValue: '1',
        defaultValue: 0,
      });
      return new cloudwatch.Metric({ namespace: 'HandWash/Ops', metricName, statistic: 'Sum', period: Duration.minutes(5) });
    };

    alarm('BackendNoHealthyTaskAlarm',
      targetGroup.metrics.healthyHostCount({ statistic: 'Minimum', period: Duration.minutes(1) }),
      1, cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD, 3,
      'El backend Java no tiene ninguna tarea sana: el kiosco no evalúa lavados.', cloudwatch.TreatMissingData.BREACHING);
    alarm('Backend5xxAlarm',
      targetGroup.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { statistic: 'Sum', period: Duration.minutes(5) }),
      5, cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, 1,
      'El backend respondió 5xx cinco o más veces en 5 minutos.');
    alarm('BackendLatencyAlarm',
      targetGroup.metrics.targetResponseTime({ statistic: 'p95', period: Duration.minutes(5) }),
      1.5, cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD, 3,
      'La latencia p95 del backend supera 1,5 s durante 15 minutos.');
    alarm('DatabaseCpuAlarm', database.metricCPUUtilization({ period: Duration.minutes(5) }),
      80, cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD, 3,
      'La CPU de la base de datos supera 80 % durante 15 minutos.');
    alarm('DatabaseFreeStorageAlarm', database.metricFreeStorageSpace({ period: Duration.minutes(15) }),
      10 * 1024 ** 3, cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD, 1,
      'Quedan menos de 10 GiB libres en la base de datos.');
    alarm('FrictionPersistenceFailedAlarm',
      ops('FRICTION_PERSISTENCE_FAILED', 'FrictionPersistenceFailed', '"OPS_ALERT code=FRICTION_PERSISTENCE_FAILED"'),
      3, cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, 1,
      'Java no pudo guardar resultados de lavado (3 o más en 5 minutos): los lavados quedan pendientes.');
    alarm('AuditChainBrokenAlarm',
      new cloudwatch.Metric({ namespace: 'HandWash/Ops', metricName: 'AuditChainBroken', statistic: 'Sum', period: Duration.hours(1) }),
      1, cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, 1,
      'La verificación horaria de la cadena de auditoría detectó una inconsistencia.');
    new logs.MetricFilter(this, 'AuditChainBrokenFilter', {
      logGroup: appLogs,
      filterPattern: logs.FilterPattern.literal('"OPS_ALERT code=AUDIT_CHAIN_BROKEN"'),
      metricNamespace: 'HandWash/Ops', metricName: 'AuditChainBroken', metricValue: '1', defaultValue: 0,
    });
    alarm('ProducerSilentAlarm',
      ops('PRODUCER_SILENT', 'ProducerSilent', '"OPS_ALERT code=PRODUCER_SILENT"'),
      3, cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, 1,
      'Durante un turno, el PC con YOLO lleva más de 3 minutos sin enviar señal a Java.');
    alarm('OtherOpsAlertsAlarm',
      ops('OTHER', 'OtherOpsAlerts',
        '?"OPS_ALERT code=SHIFT_SESSION_START_FAILED" ?"OPS_ALERT code=PHOTO_UPLOAD_UNAVAILABLE" ?"OPS_ALERT code=AUDIT_SEAL_FAILED"')
        .with({ period: Duration.minutes(15) }),
      3, cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, 1,
      'El turno no pudo abrir sesión, fallaron subidas de foto o falló el sello diario de auditoría.');
    new cdk.CfnOutput(this, 'AlertTopicArn', { value: alertTopic.topicArn });

    new cdk.CfnOutput(this, 'BackendHttpsUrl', {
      value: `https://${distribution.distributionDomainName}`,
      description: 'Backend HTTPS/WSS endpoint; this stack does not deploy the web frontend.',
    });
    new cdk.CfnOutput(this, 'CognitoIssuer', {
      value: `https://cognito-idp.${this.region}.amazonaws.com/${userPool.userPoolId}`,
    });
    new cdk.CfnOutput(this, 'CognitoSpaClientId', { value: spaClient.userPoolClientId });
    new cdk.CfnOutput(this, 'OperatorIssuer', {
      value: `https://cognito-idp.${this.region}.amazonaws.com/${operatorPool.userPoolId}`,
    });
    new cdk.CfnOutput(this, 'OperatorClientId', { value: operatorClient.userPoolClientId });
    new cdk.CfnOutput(this, 'OperatorDomain', {
      value: `https://handwash-operators-${this.account}.auth.${this.region}.amazoncognito.com`,
    });
    new cdk.CfnOutput(this, 'CognitoProducerClientId', { value: producerClient.userPoolClientId });
    new cdk.CfnOutput(this, 'CognitoDomain', {
      value: `https://handwash-pilot-${this.account}.auth.${this.region}.amazoncognito.com`,
    });
    new cdk.CfnOutput(this, 'BackendImageRepositoryUri', { value: backendRepository.repositoryUri });
    new cdk.CfnOutput(this, 'BackendImageTag', { value: imageTag });
    new cdk.CfnOutput(this, 'DatabaseSecretArn', {
      value: runtimeSecret.secretArn,
      description: 'Runtime DB secret ARN; secret contents are never output.',
    });
    new cdk.CfnOutput(this, 'DatabaseMigrationSecretArn', {
      value: migrationSecret.secretArn,
      description: 'Flyway DB secret ARN; secret contents are never output.',
    });
  }
}
