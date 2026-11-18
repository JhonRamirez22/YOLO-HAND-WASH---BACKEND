# Java backend cloud pilot

Deploys only the Spring Boot backend and its required AWS services in `sa-east-1`: ECS Fargate, private Multi-AZ RDS PostgreSQL, Cognito, and a CloudFront HTTPS/WSS origin for the ALB. It does not deploy React, YOLO, model weights, camera video, or training jobs. CodeBuild creates the Java container from a curated backend source asset; the source upload excludes models, datasets, and generated `target/` files. The stack waits for that image before starting ECS.

The service is intentionally one task because session state and Java arbitration are in memory. ECS replacement may interrupt an active session; do not use this pilot for live hospital workflows. All clinical/photo/video features remain disabled by the cloud profile.

The temporary CloudFront hostname provides HTTPS/WSS to browsers. Its AWS default certificate does not allow setting a custom minimum TLS policy; attach an owned domain and ACM certificate before any stricter transport-policy requirement or production use. CloudFront-to-ALB traffic is HTTP inside AWS, restricted by the CloudFront managed prefix list and a per-distribution secret origin header; this is not end-to-end TLS.

## Prerequisites

- Node.js 20+, npm, and an authorized, short-lived AWS session. A local Docker daemon is not required; CodeBuild builds the Linux image in AWS.
- An authorized, short-lived AWS session with CDK bootstrap/deployment permissions. This stack uses the existing authenticated profile; it creates no long-lived AWS access keys.
- AWS account deployment region: `sa-east-1`.

## Commands

```bash
npm ci
npm test
npm run typecheck
npm run synth
npm run diff
npm run deploy
```

`synth` resolves the AWS-managed CloudFront origin-facing prefix list for the account/region. Review the `diff` before deployment. Initial CDK bootstrap may be required and needs near-administrator permissions; deployment uses standard CloudFormation/CDK roles.

After deployment, test `https://<BackendHttpsUrl>/actuator/health`. Add Cognito users through the AWS console and assign only the required `OPERATOR` or `ADMIN` group. MFA is required. The producer client secret is not emitted as a stack output.

The configured model metadata is explicitly non-validated and does not upload or load any checkpoint. Replace the release metadata only when the RTX producer and its exact model/data manifest are ready. The API endpoint is deployed separately from the React app; its shared CloudFront hostname can receive the frontend origin in a later stack update. Until then, the Cognito callback origin is reserved but does not serve the web UI.
