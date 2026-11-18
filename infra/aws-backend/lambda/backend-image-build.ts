import {
  BatchGetBuildsCommand,
  CodeBuildClient,
  StartBuildCommand,
} from '@aws-sdk/client-codebuild';

interface BuildResourceEvent {
  readonly RequestType: 'Create' | 'Update' | 'Delete';
  readonly PhysicalResourceId?: string;
  readonly ResourceProperties: Record<string, unknown>;
  readonly Data?: Record<string, unknown>;
}

const codeBuild = new CodeBuildClient({ region: process.env.AWS_REGION ?? 'sa-east-1' });

export async function startBuild(event: BuildResourceEvent) {
  const imageTag = requiredProperty(event.ResourceProperties, 'ImageTag');
  const physicalResourceId = `handwash-backend-image:${imageTag}`;
  if (event.RequestType === 'Delete') {
    return { PhysicalResourceId: event.PhysicalResourceId ?? physicalResourceId };
  }

  const projectName = requiredEnvironment('CODEBUILD_PROJECT_NAME');
  const response = await codeBuild.send(new StartBuildCommand({
    projectName,
    idempotencyToken: imageTag,
  }));
  const buildId = response.build?.id;
  if (!buildId) throw new Error('CodeBuild did not return a build ID.');

  console.info(`Started Java backend image build ${buildId} for ${imageTag}.`);
  return { PhysicalResourceId: physicalResourceId, Data: { BuildId: buildId } };
}

export async function checkBuild(event: BuildResourceEvent): Promise<{ IsComplete: boolean }> {
  if (event.RequestType === 'Delete') return { IsComplete: true };

  const buildId = event.Data?.BuildId;
  if (typeof buildId !== 'string' || !buildId.trim()) {
    throw new Error('The CodeBuild waiter received no build ID.');
  }
  const response = await codeBuild.send(new BatchGetBuildsCommand({ ids: [buildId] }));
  const build = response.builds?.find((candidate) => candidate.id === buildId);
  if (!build) throw new Error(`CodeBuild build ${buildId} was not found.`);

  const status = build.buildStatus;
  const outcome = classifyBuildStatus(status);
  if (outcome === 'succeeded') {
    console.info(`Java backend image build ${buildId} succeeded.`);
    return { IsComplete: true };
  }
  if (outcome === 'failed') {
    const logLink = build.logs?.deepLink;
    throw new Error(`Java backend image build ${status}.${logLink ? ` Build logs: ${logLink}` : ''}`);
  }
  return { IsComplete: false };
}

export function classifyBuildStatus(status: string | undefined): 'pending' | 'succeeded' | 'failed' {
  if (status === 'SUCCEEDED') return 'succeeded';
  if (status && ['FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT'].includes(status)) return 'failed';
  return 'pending';
}

function requiredProperty(properties: Record<string, unknown>, name: string): string {
  const value = properties[name];
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`Custom resource property ${name} is required.`);
  }
  return value;
}

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Environment variable ${name} is required.`);
  return value;
}
