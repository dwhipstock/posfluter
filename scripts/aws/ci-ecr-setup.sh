#!/usr/bin/env bash
# One-time (idempotent) AWS setup for CI → ECR → box pull (S5c / rec 6).
#
# Creates, without minting any long-lived credential:
#   1. a GitHub Actions OIDC identity provider in IAM
#   2. role `pos-ci-ecr-push`, assumable ONLY by this repo's Actions workflows
#      via OIDC, allowed to push/pull the four ECR repos
#   3. the four ECR repos (IMMUTABLE tags → a :sha image can never be overwritten;
#      scan-on-push on)
#   4. an ECR *pull* policy on the existing box instance role, so the t4g box
#      pulls images with its instance profile (no keys on the box either)
#
# CI authenticates by assuming (2) via OIDC; the box pulls via (4). Re-run any
# time — every step is create-or-noop.
#
# Requires: aws cli with admin (run from the laptop that has the admin profile).
set -euo pipefail
export AWS_PAGER=""

ACCOUNT="${ACCOUNT:-842588910054}"
REGION="${AWS_REGION:-us-east-1}"
GH_REPO="${GH_REPO:-dwhipstock/pos}"
CI_ROLE="${CI_ROLE:-pos-ci-ecr-push}"
BOX_ROLE="${BOX_ROLE:-pos-caddy-route53}"        # the existing instance-profile role
REPOS=(pos-store pos-cloud-api pos-cloud-web pos-cloud-caddy)
OIDC_HOST="token.actions.githubusercontent.com"
OIDC_ARN="arn:aws:iam::${ACCOUNT}:oidc-provider/${OIDC_HOST}"

echo "== 1. GitHub OIDC provider =="
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  echo "-- provider exists"
else
  # Thumbprint is legacy (IAM now validates GitHub's token against its trusted
  # CA store), but the API still requires one. This is GitHub's long-published value.
  aws iam create-open-id-connect-provider \
    --url "https://${OIDC_HOST}" \
    --client-id-list "sts.amazonaws.com" \
    --thumbprint-list "6938fd4d98bab03faadb97b34396831e3780aea1" >/dev/null
  echo "-- provider created"
fi

echo "== 2. CI role ${CI_ROLE} (OIDC trust, scoped to ${GH_REPO} main + tags) =="
TRUST=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "${OIDC_ARN}" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "${OIDC_HOST}:aud": "sts.amazonaws.com" },
      "StringLike": {
        "${OIDC_HOST}:sub": [
          "repo:${GH_REPO}:ref:refs/heads/main",
          "repo:${GH_REPO}:ref:refs/tags/*"
        ]
      }
    }
  }]
}
JSON
)
if aws iam get-role --role-name "$CI_ROLE" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$CI_ROLE" --policy-document "$TRUST" >/dev/null
  echo "-- trust policy updated"
else
  aws iam create-role --role-name "$CI_ROLE" --assume-role-policy-document "$TRUST" \
    --description "GitHub Actions pushes POS images to ECR via OIDC (no static keys)" >/dev/null
  echo "-- role created"
fi

# REGION/ACCOUNT are baked into the format string, not passed as args: a printf with
# %s-per-field would recycle the format across ${REPOS[@]} and mangle every repo after the
# first into one invalid ARN. One %s (the repo name) cycles once per repo.
REPO_ARNS=$(printf "\"arn:aws:ecr:${REGION}:${ACCOUNT}:repository/%s\"," "${REPOS[@]}" | sed 's/,$//')
PUSH_POLICY=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "AuthToken", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "PushPull", "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage",
        "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"
      ],
      "Resource": [ ${REPO_ARNS} ] }
  ]
}
JSON
)
aws iam put-role-policy --role-name "$CI_ROLE" --policy-name ecr-push --policy-document "$PUSH_POLICY" >/dev/null
echo "-- ecr-push inline policy attached"

echo "== 3. ECR repos (immutable tags, scan on push) =="
for r in "${REPOS[@]}"; do
  if aws ecr describe-repositories --repository-names "$r" --region "$REGION" >/dev/null 2>&1; then
    echo "-- $r exists"
  else
    aws ecr create-repository --repository-name "$r" --region "$REGION" \
      --image-tag-mutability IMMUTABLE \
      --image-scanning-configuration scanOnPush=true >/dev/null
    echo "-- $r created"
  fi
done

echo "== 4. ECR pull on the box instance role ${BOX_ROLE} =="
PULL_POLICY=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "AuthToken", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "Pull", "Effect": "Allow",
      "Action": [ "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage" ],
      "Resource": [ ${REPO_ARNS} ] }
  ]
}
JSON
)
aws iam put-role-policy --role-name "$BOX_ROLE" --policy-name ecr-pull --policy-document "$PULL_POLICY" >/dev/null
echo "-- ecr-pull inline policy attached to ${BOX_ROLE}"

echo
echo "DONE."
echo "  Registry:  ${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
echo "  CI role:   arn:aws:iam::${ACCOUNT}:role/${CI_ROLE}"
echo "  Repos:     ${REPOS[*]}"
