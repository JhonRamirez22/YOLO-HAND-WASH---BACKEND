import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyBuildStatus } from '../lambda/backend-image-build.js';

test('image build waiter keeps queued and running builds pending', () => {
  assert.equal(classifyBuildStatus(undefined), 'pending');
  assert.equal(classifyBuildStatus('QUEUED'), 'pending');
  assert.equal(classifyBuildStatus('IN_PROGRESS'), 'pending');
});

test('image build waiter accepts only successful builds and surfaces terminal failures', () => {
  assert.equal(classifyBuildStatus('SUCCEEDED'), 'succeeded');
  for (const status of ['FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT']) {
    assert.equal(classifyBuildStatus(status), 'failed');
  }
});
